package org.example.algorithmdebug.harness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * 一个异步外部进程的唯一资源所有者。
 *
 * <p>调用方必须最终调用 {@link #await(Duration)} 或 {@link #close()}。关闭操作幂等，并清理完整后代树。</p>
 */
public final class ManagedProcess implements AutoCloseable {
    private final Process process;
    private final ProcessSupervisor supervisor;
    private final ProcessLimits limits;
    private final OutputMarkerRegistry markers;
    private final ExecutorService pumps;
    private final Future<RunLog> stdoutFuture;
    private final Future<RunLog> stderrFuture;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Instant startedAt;
    private final long startedNanos;
    private RunResult completed;
    private boolean closed;

    ManagedProcess(
            Process process,
            ProcessSupervisor supervisor,
            ProcessLimits limits,
            OutputMarkerRegistry markers,
            ExecutorService pumps,
            Future<RunLog> stdoutFuture,
            Future<RunLog> stderrFuture,
            Clock clock,
            LongSupplier nanoTime,
            Instant startedAt,
            long startedNanos) {
        this.process = process;
        this.supervisor = supervisor;
        this.limits = limits;
        this.markers = markers;
        this.pumps = pumps;
        this.stdoutFuture = stdoutFuture;
        this.stderrFuture = stderrFuture;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.startedAt = startedAt;
        this.startedNanos = startedNanos;
    }

    /** 返回根进程 PID。 */
    public long pid() {
        return process.pid();
    }

    /** 返回根进程当前是否仍存活。 */
    public boolean isAlive() {
        return process.isAlive();
    }

    /** 等待已在启动规格中登记的 stdout 或 stderr 标记，不主动终止进程。 */
    public ProcessOutputWaitResult awaitOutput(String marker, Duration timeout)
            throws HarnessException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("输出等待 timeout 必须为正数");
        }
        CompletableFuture<Void> observed = markers.future(marker);
        if (observed.isDone()) {
            return ProcessOutputWaitResult.OBSERVED;
        }
        try {
            CompletableFuture.anyOf(observed, process.onExit())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return observed.isDone()
                    ? ProcessOutputWaitResult.OBSERVED
                    : ProcessOutputWaitResult.PROCESS_EXITED;
        } catch (TimeoutException failure) {
            return ProcessOutputWaitResult.TIMED_OUT;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new HarnessException("HARNESS_RUN_INTERRUPTED", "等待进程输出标记时被中断", failure);
        } catch (ExecutionException failure) {
            throw new HarnessException("HARNESS_OUTPUT_WAIT_FAILED", "等待进程输出标记失败", failure.getCause());
        }
    }

    /** 等待退出；超过预算时终止完整进程树并返回 TIMED_OUT。 */
    public synchronized RunResult await(Duration timeout) throws HarnessException {
        if (completed != null) {
            return completed;
        }
        if (closed) {
            throw new HarnessException("HARNESS_PROCESS_ALREADY_CLOSED", "受管进程已关闭，无法再次等待");
        }
        try {
            SupervisionResult supervision = supervisor.await(process, timeout, limits);
            RunLog stdout = awaitLog(stdoutFuture);
            RunLog stderr = awaitLog(stderrFuture);
            RunCompletion completion = supervision.timedOut() ? RunCompletion.TIMED_OUT
                    : supervision.exitCode().orElseThrow() == 0
                            ? RunCompletion.SUCCEEDED : RunCompletion.FAILED;
            completed = new RunResult(
                    completion, supervision.exitCode(), startedAt, clock.instant(),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startedNanos)), process.pid(),
                    stdout, stderr, supervision.termination());
            return completed;
        } finally {
            pumps.shutdownNow();
        }
    }

    /** 幂等关闭；仍存活时清理完整进程树，并等待日志泵退出。 */
    @Override
    public synchronized void close() throws HarnessException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (process.isAlive()) {
                supervisor.terminate(process, limits);
            }
            awaitLog(stdoutFuture);
            awaitLog(stderrFuture);
        } finally {
            pumps.shutdownNow();
        }
    }

    private RunLog awaitLog(Future<RunLog> future) throws HarnessException {
        try {
            return future.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (process.isAlive()) {
                supervisor.terminate(process, limits);
            }
            throw new HarnessException("HARNESS_RUN_INTERRUPTED", "等待受管进程日志时被中断", failure);
        } catch (ExecutionException failure) {
            if (process.isAlive()) {
                supervisor.terminate(process, limits);
            }
            Throwable cause = failure.getCause();
            if (cause instanceof HarnessException harness) {
                throw harness;
            }
            throw new HarnessException("HARNESS_LOG_CAPTURE_FAILED", "受管进程日志归档失败", cause);
        }
    }
}
