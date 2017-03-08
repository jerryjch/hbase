/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import static org.apache.hadoop.hbase.client.ConnectionUtils.SLEEP_DELTA_NS;
import static org.apache.hadoop.hbase.client.ConnectionUtils.getPauseTime;
import static org.apache.hadoop.hbase.client.ConnectionUtils.noMoreResultsForReverseScan;
import static org.apache.hadoop.hbase.client.ConnectionUtils.noMoreResultsForScan;
import static org.apache.hadoop.hbase.client.ConnectionUtils.numberOfIndividualRows;
import static org.apache.hadoop.hbase.client.ConnectionUtils.resetController;
import static org.apache.hadoop.hbase.client.ConnectionUtils.translateException;

import com.google.common.base.Preconditions;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.UnknownScannerException;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.RawScanResultConsumer.ScanResumer;
import org.apache.hadoop.hbase.exceptions.OutOfOrderScannerNextException;
import org.apache.hadoop.hbase.exceptions.ScannerResetException;
import org.apache.hadoop.hbase.ipc.HBaseRpcController;
import org.apache.hadoop.hbase.regionserver.RegionServerStoppedException;
import org.apache.hadoop.hbase.shaded.protobuf.RequestConverter;
import org.apache.hadoop.hbase.shaded.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ClientService;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ClientService.Interface;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ScanRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ScanResponse;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

/**
 * Retry caller for scanning a region.
 * <p>
 * We will modify the {@link Scan} object passed in directly. The upper layer should store the
 * reference of this object and use it to open new single region scanners.
 */
@InterfaceAudience.Private
class AsyncScanSingleRegionRpcRetryingCaller {

  private static final Log LOG = LogFactory.getLog(AsyncScanSingleRegionRpcRetryingCaller.class);

  private final HashedWheelTimer retryTimer;

  private final Scan scan;

  private final long scannerId;

  private final ScanResultCache resultCache;

  private final RawScanResultConsumer consumer;

  private final ClientService.Interface stub;

  private final HRegionLocation loc;

  private final long scannerLeaseTimeoutPeriodNs;

  private final long pauseNs;

  private final int maxAttempts;

  private final long scanTimeoutNs;

  private final long rpcTimeoutNs;

  private final int startLogErrorsCnt;

  private final Runnable completeWhenNoMoreResultsInRegion;

  private final CompletableFuture<Boolean> future;

  private final HBaseRpcController controller;

  private byte[] nextStartRowWhenError;

  private boolean includeNextStartRowWhenError;

  private long nextCallStartNs;

  private int tries = 1;

  private final List<RetriesExhaustedException.ThrowableWithExtraContext> exceptions;

  private long nextCallSeq = -1L;

  private enum ScanControllerState {
    INITIALIZED, SUSPENDED, TERMINATED, DESTROYED
  }

  // Since suspend and terminate should only be called within onNext or onHeartbeat(see the comments
  // of RawScanResultConsumer.onNext and onHeartbeat), we need to add some check to prevent invalid
  // usage. We use two things to prevent invalid usage:
  // 1. Record the thread that construct the ScanControllerImpl instance. We will throw an
  // IllegalStateException if the caller thread is not this thread.
  // 2. The ControllerState. The initial state is INITIALIZED, if you call suspend, the state will
  // be transformed to SUSPENDED, and if you call terminate, the state will be transformed to
  // TERMINATED. And when we are back from onNext or onHeartbeat in the onComplete method, we will
  // call destroy to get the current state and set the state to DESTROYED. And when user calls
  // suspend or terminate, we will check if the current state is INITIALIZED, if not we will throw
  // an IllegalStateException. Notice that the DESTROYED state is necessary as you may not call
  // suspend or terminate so the state will still be INITIALIZED when back from onNext or
  // onHeartbeat. We need another state to replace the INITIALIZED state to prevent the controller
  // to be used in the future.
  // Notice that, the public methods of this class is supposed to be called by upper layer only, and
  // package private methods can only be called within the implementation of
  // AsyncScanSingleRegionRpcRetryingCaller.
  private final class ScanControllerImpl implements RawScanResultConsumer.ScanController {

    // Make sure the methods are only called in this thread.
    private final Thread callerThread = Thread.currentThread();

    // INITIALIZED -> SUSPENDED -> DESTROYED
    // INITIALIZED -> TERMINATED -> DESTROYED
    // INITIALIZED -> DESTROYED
    // If the state is incorrect we will throw IllegalStateException.
    private ScanControllerState state = ScanControllerState.INITIALIZED;

    private ScanResumerImpl resumer;

    private void preCheck() {
      Preconditions.checkState(Thread.currentThread() == callerThread,
        "The current thread is %s, expected thread is %s, " +
            "you should not call this method outside onNext or onHeartbeat",
        Thread.currentThread(), callerThread);
      Preconditions.checkState(state.equals(ScanControllerState.INITIALIZED),
        "Invalid Stopper state %s", state);
    }

    @Override
    public ScanResumer suspend() {
      preCheck();
      state = ScanControllerState.SUSPENDED;
      ScanResumerImpl resumer = new ScanResumerImpl();
      this.resumer = resumer;
      return resumer;
    }

    @Override
    public void terminate() {
      preCheck();
      state = ScanControllerState.TERMINATED;
    }

    // return the current state, and set the state to DESTROYED.
    ScanControllerState destroy() {
      ScanControllerState state = this.state;
      this.state = ScanControllerState.DESTROYED;
      return state;
    }
  }

  private enum ScanResumerState {
    INITIALIZED, SUSPENDED, RESUMED
  }

  // The resume method is allowed to be called in another thread so here we also use the
  // ResumerState to prevent race. The initial state is INITIALIZED, and in most cases, when back
  // from onNext or onHeartbeat, we will call the prepare method to change the state to SUSPENDED,
  // and when user calls resume method, we will change the state to RESUMED. But the resume method
  // could be called in other thread, and in fact, user could just do this:
  // controller.suspend().resume()
  // This is strange but valid. This means the scan could be resumed before we call the prepare
  // method to do the actual suspend work. So in the resume method, we will check if the state is
  // INTIALIZED, if it is, then we will just set the state to RESUMED and return. And in prepare
  // method, if the state is RESUMED already, we will just return an let the scan go on.
  // Notice that, the public methods of this class is supposed to be called by upper layer only, and
  // package private methods can only be called within the implementation of
  // AsyncScanSingleRegionRpcRetryingCaller.
  private final class ScanResumerImpl implements RawScanResultConsumer.ScanResumer {

    // INITIALIZED -> SUSPENDED -> RESUMED
    // INITIALIZED -> RESUMED
    private ScanResumerState state = ScanResumerState.INITIALIZED;

    private ScanResponse resp;

    private int numberOfIndividualRows;

    // If the scan is suspended successfully, we need to do lease renewal to prevent it being closed
    // by RS due to lease expire. It is a one-time timer task so we need to schedule a new task
    // every time when the previous task is finished. There could also be race as the renewal is
    // executed in the timer thread, so we also need to check the state before lease renewal. If the
    // state is RESUMED already, we will give up lease renewal and also not schedule the next lease
    // renewal task.
    private Timeout leaseRenewer;

    @Override
    public void resume() {
      // just used to fix findbugs warnings. In fact, if resume is called before prepare, then we
      // just return at the first if condition without loading the resp and numValidResuls field. If
      // resume is called after suspend, then it is also safe to just reference resp and
      // numValidResults after the synchronized block as no one will change it anymore.
      ScanResponse localResp;
      int localNumberOfIndividualRows;
      synchronized (this) {
        if (state == ScanResumerState.INITIALIZED) {
          // user calls this method before we call prepare, so just set the state to
          // RESUMED, the implementation will just go on.
          state = ScanResumerState.RESUMED;
          return;
        }
        if (state == ScanResumerState.RESUMED) {
          // already resumed, give up.
          return;
        }
        state = ScanResumerState.RESUMED;
        if (leaseRenewer != null) {
          leaseRenewer.cancel();
        }
        localResp = this.resp;
        localNumberOfIndividualRows = this.numberOfIndividualRows;
      }
      completeOrNext(localResp, localNumberOfIndividualRows);
    }

    private void scheduleRenewLeaseTask() {
      leaseRenewer = retryTimer.newTimeout(t -> tryRenewLease(), scannerLeaseTimeoutPeriodNs / 2,
        TimeUnit.NANOSECONDS);
    }

    private synchronized void tryRenewLease() {
      // the scan has already been resumed, give up
      if (state == ScanResumerState.RESUMED) {
        return;
      }
      renewLease();
      // schedule the next renew lease task again as this is a one-time task.
      scheduleRenewLeaseTask();
    }

    // return false if the scan has already been resumed. See the comment above for ScanResumerImpl
    // for more details.
    synchronized boolean prepare(ScanResponse resp, int numberOfIndividualRows) {
      if (state == ScanResumerState.RESUMED) {
        // user calls resume before we actually suspend the scan, just continue;
        return false;
      }
      state = ScanResumerState.SUSPENDED;
      this.resp = resp;
      this.numberOfIndividualRows = numberOfIndividualRows;
      // if there are no more results in region then the scanner at RS side will be closed
      // automatically so we do not need to renew lease.
      if (resp.getMoreResultsInRegion()) {
        // schedule renew lease task
        scheduleRenewLeaseTask();
      }
      return true;
    }
  }

  public AsyncScanSingleRegionRpcRetryingCaller(HashedWheelTimer retryTimer,
      AsyncConnectionImpl conn, Scan scan, long scannerId, ScanResultCache resultCache,
      RawScanResultConsumer consumer, Interface stub, HRegionLocation loc,
      long scannerLeaseTimeoutPeriodNs, long pauseNs, int maxAttempts, long scanTimeoutNs,
      long rpcTimeoutNs, int startLogErrorsCnt) {
    this.retryTimer = retryTimer;
    this.scan = scan;
    this.scannerId = scannerId;
    this.resultCache = resultCache;
    this.consumer = consumer;
    this.stub = stub;
    this.loc = loc;
    this.scannerLeaseTimeoutPeriodNs = scannerLeaseTimeoutPeriodNs;
    this.pauseNs = pauseNs;
    this.maxAttempts = maxAttempts;
    this.scanTimeoutNs = scanTimeoutNs;
    this.rpcTimeoutNs = rpcTimeoutNs;
    this.startLogErrorsCnt = startLogErrorsCnt;
    if (scan.isReversed()) {
      completeWhenNoMoreResultsInRegion = this::completeReversedWhenNoMoreResultsInRegion;
    } else {
      completeWhenNoMoreResultsInRegion = this::completeWhenNoMoreResultsInRegion;
    }
    this.future = new CompletableFuture<>();
    this.controller = conn.rpcControllerFactory.newController();
    this.exceptions = new ArrayList<>();
  }

  private long elapsedMs() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nextCallStartNs);
  }

  private long remainingTimeNs() {
    return scanTimeoutNs - (System.nanoTime() - nextCallStartNs);
  }

  private void closeScanner() {
    resetController(controller, rpcTimeoutNs);
    ScanRequest req = RequestConverter.buildScanRequest(this.scannerId, 0, true, false);
    stub.scan(controller, req, resp -> {
      if (controller.failed()) {
        LOG.warn("Call to " + loc.getServerName() + " for closing scanner id = " + scannerId +
            " for " + loc.getRegionInfo().getEncodedName() + " of " +
            loc.getRegionInfo().getTable() + " failed, ignore, probably already closed",
          controller.getFailed());
      }
    });
  }

  private void completeExceptionally(boolean closeScanner) {
    resultCache.clear();
    if (closeScanner) {
      closeScanner();
    }
    future.completeExceptionally(new RetriesExhaustedException(tries - 1, exceptions));
  }

  private void completeNoMoreResults() {
    future.complete(false);
  }

  private void completeWithNextStartRow(byte[] row, boolean inclusive) {
    scan.withStartRow(row, inclusive);
    future.complete(true);
  }

  private void completeWhenError(boolean closeScanner) {
    resultCache.clear();
    if (closeScanner) {
      closeScanner();
    }
    if (nextStartRowWhenError != null) {
      scan.withStartRow(nextStartRowWhenError, includeNextStartRowWhenError);
    }
    future.complete(true);
  }

  private void onError(Throwable error) {
    error = translateException(error);
    if (tries > startLogErrorsCnt) {
      LOG.warn("Call to " + loc.getServerName() + " for scanner id = " + scannerId + " for " +
          loc.getRegionInfo().getEncodedName() + " of " + loc.getRegionInfo().getTable() +
          " failed, , tries = " + tries + ", maxAttempts = " + maxAttempts + ", timeout = " +
          TimeUnit.NANOSECONDS.toMillis(scanTimeoutNs) + " ms, time elapsed = " + elapsedMs() +
          " ms",
        error);
    }
    boolean scannerClosed = error instanceof UnknownScannerException ||
        error instanceof NotServingRegionException || error instanceof RegionServerStoppedException;
    RetriesExhaustedException.ThrowableWithExtraContext qt =
        new RetriesExhaustedException.ThrowableWithExtraContext(error,
            EnvironmentEdgeManager.currentTime(), "");
    exceptions.add(qt);
    if (tries >= maxAttempts) {
      completeExceptionally(!scannerClosed);
      return;
    }
    long delayNs;
    if (scanTimeoutNs > 0) {
      long maxDelayNs = remainingTimeNs() - SLEEP_DELTA_NS;
      if (maxDelayNs <= 0) {
        completeExceptionally(!scannerClosed);
        return;
      }
      delayNs = Math.min(maxDelayNs, getPauseTime(pauseNs, tries - 1));
    } else {
      delayNs = getPauseTime(pauseNs, tries - 1);
    }
    if (scannerClosed) {
      completeWhenError(false);
      return;
    }
    if (error instanceof OutOfOrderScannerNextException || error instanceof ScannerResetException) {
      completeWhenError(true);
      return;
    }
    if (error instanceof DoNotRetryIOException) {
      completeExceptionally(true);
      return;
    }
    tries++;
    retryTimer.newTimeout(t -> call(), delayNs, TimeUnit.NANOSECONDS);
  }

  private void updateNextStartRowWhenError(Result result) {
    nextStartRowWhenError = result.getRow();
    includeNextStartRowWhenError = result.mayHaveMoreCellsInRow();
  }

  private void completeWhenNoMoreResultsInRegion() {
    if (noMoreResultsForScan(scan, loc.getRegionInfo())) {
      completeNoMoreResults();
    } else {
      completeWithNextStartRow(loc.getRegionInfo().getEndKey(), true);
    }
  }

  private void completeReversedWhenNoMoreResultsInRegion() {
    if (noMoreResultsForReverseScan(scan, loc.getRegionInfo())) {
      completeNoMoreResults();
    } else {
      completeWithNextStartRow(loc.getRegionInfo().getStartKey(), false);
    }
  }

  private void completeOrNext(ScanResponse resp, int numIndividualRows) {
    if (resp.hasMoreResults() && !resp.getMoreResults()) {
      // RS tells us there is no more data for the whole scan
      completeNoMoreResults();
      return;
    }
    if (scan.getLimit() > 0) {
      // The RS should have set the moreResults field in ScanResponse to false when we have reached
      // the limit, so we add an assert here.
      int newLimit = scan.getLimit() - numIndividualRows;
      assert newLimit > 0;
      scan.setLimit(newLimit);
    }
    // as in 2.0 this value will always be set
    if (!resp.getMoreResultsInRegion()) {
      completeWhenNoMoreResultsInRegion.run();
      return;
    }
    next();
  }

  private void onComplete(HBaseRpcController controller, ScanResponse resp) {
    if (controller.failed()) {
      onError(controller.getFailed());
      return;
    }
    boolean isHeartbeatMessage = resp.hasHeartbeatMessage() && resp.getHeartbeatMessage();
    Result[] results;
    try {
      results = resultCache.addAndGet(
        Optional.ofNullable(ResponseConverter.getResults(controller.cellScanner(), resp))
            .orElse(ScanResultCache.EMPTY_RESULT_ARRAY),
        isHeartbeatMessage);
    } catch (IOException e) {
      // We can not retry here. The server has responded normally and the call sequence has been
      // increased so a new scan with the same call sequence will cause an
      // OutOfOrderScannerNextException. Let the upper layer open a new scanner.
      LOG.warn("decode scan response failed", e);
      completeWhenError(true);
      return;
    }
    // calculate this before calling onNext as it is free for user to modify the result array in
    // onNext.
    int numberOfIndividualRows = numberOfIndividualRows(Arrays.asList(results));
    ScanControllerImpl scanController = new ScanControllerImpl();
    if (results.length == 0) {
      // if we have nothing to return then just call onHeartbeat.
      consumer.onHeartbeat(scanController);
    } else {
      updateNextStartRowWhenError(results[results.length - 1]);
      consumer.onNext(results, scanController);
    }
    ScanControllerState state = scanController.destroy();
    if (state == ScanControllerState.TERMINATED) {
      if (resp.getMoreResultsInRegion()) {
        // we have more results in region but user request to stop the scan, so we need to close the
        // scanner explicitly.
        closeScanner();
      }
      completeNoMoreResults();
      return;
    }
    if (state == ScanControllerState.SUSPENDED) {
      if (scanController.resumer.prepare(resp, numberOfIndividualRows)) {
        return;
      }
    }
    completeOrNext(resp, numberOfIndividualRows);
  }

  private void call() {
    // As we have a call sequence for scan, it is useless to have a different rpc timeout which is
    // less than the scan timeout. If the server does not respond in time(usually this will not
    // happen as we have heartbeat now), we will get an OutOfOrderScannerNextException when
    // resending the next request and the only way to fix this is to close the scanner and open a
    // new one.
    long callTimeoutNs;
    if (scanTimeoutNs > 0) {
      long remainingNs = scanTimeoutNs - (System.nanoTime() - nextCallStartNs);
      if (remainingNs <= 0) {
        completeExceptionally(true);
        return;
      }
      callTimeoutNs = remainingNs;
    } else {
      callTimeoutNs = 0L;
    }
    resetController(controller, callTimeoutNs);
    ScanRequest req = RequestConverter.buildScanRequest(scannerId, scan.getCaching(), false,
      nextCallSeq, false, false, scan.getLimit());
    stub.scan(controller, req, resp -> onComplete(controller, resp));
  }

  private void next() {
    nextCallSeq++;
    tries = 0;
    exceptions.clear();
    nextCallStartNs = System.nanoTime();
    call();
  }

  private void renewLease() {
    nextCallSeq++;
    resetController(controller, rpcTimeoutNs);
    ScanRequest req =
        RequestConverter.buildScanRequest(scannerId, 0, false, nextCallSeq, false, true, -1);
    stub.scan(controller, req, resp -> {
    });
  }

  /**
   * Now we will also fetch some cells along with the scanner id when opening a scanner, so we also
   * need to process the ScanResponse for the open scanner request. The HBaseRpcController for the
   * open scanner request is also needed because we may have some data in the CellScanner which is
   * contained in the controller.
   * @return {@code true} if we should continue, otherwise {@code false}.
   */
  public CompletableFuture<Boolean> start(HBaseRpcController controller,
      ScanResponse respWhenOpen) {
    onComplete(controller, respWhenOpen);
    return future;
  }
}