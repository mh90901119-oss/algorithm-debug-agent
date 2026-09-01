package org.example.algorithmdebug.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/** 为 Collector 和辅助 Maven 命令提供统一的超时、日志排空和进程树清理。 */
public final class ExternalProcessRunner {
    private final ProcessSupervisor supervisor;
    private final BoundedOutputCapture output;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final ProcessStarter starter;

    /** 使用系统端口创建 Runner。 */
    public ExternalProcessRunner() {
        this(new ProcessSupervisor(), new BoundedOutputCapture(), Clock.systemUTC(),
                System::nanoTime, ProcessBuilder::start);
    }

    ExternalProcessRunner(
            ProcessSupervisor supervisor,
            BoundedOutputCapture output,
            Clock clock,
            LongSupplier nanoTime,
            ProcessStarter starter) {
        this.supervisor = supervisor;
        this.output = output;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.starter = starter;
    }

    /** 执行 argv，不经过 shell，并返回与目标 UT 进程相同的结构化事实。 */
    public RunResult execute(
            List<String> argv,
            Path workingDirectory,
            Path stdoutPath,
            Path stderrPath,
            Duration timeout,
            ProcessLimits limits) throws HarnessException {
        if (argv == null || argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())
                || workingDirectory == null || timeout == null || limits == null) {
            throw new IllegalArgumentException("External process argv, working directory, timeout, and budget must not be null");
        }
        Instant startedAt = clock.instant();
        long startNanos = nanoTime.getAsLong();
        Path stdout = output.prepare(stdoutPath);
        Path stderr = output.prepare(stderrPath);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(List.copyOf(argv));
            builder.directory(workingDirectory.toAbsolutePath().normalize().toFile());
            process = starter.start(builder);
        } catch (IOException failure) {
            throw new HarnessException("HARNESS_PROCESS_START_FAILED", "Failed to start external process", failure);
        }
        ExecutorService pumps = Executors.newFixedThreadPool(2);
        Future<RunLog> stdoutFuture = pumps.submit(() -> output.capturePrepared(
                process.getInputStream(), stdout, limits.maximumStdoutBytes()));
        Future<RunLog> stderrFuture = pumps.submit(() -> output.capturePrepared(
                process.getErrorStream(), stderr, limits.maximumStderrBytes()));
        try {
            SupervisionResult supervision = supervisor.await(process, timeout, limits);
            RunLog stdoutLog = await(stdoutFuture, process, limits);
            RunLog stderrLog = await(stderrFuture, process, limits);
            RunCompletion completion = supervision.timedOut() ? RunCompletion.TIMED_OUT
                    : supervision.exitCode().orElseThrow() == 0
                            ? RunCompletion.SUCCEEDED : RunCompletion.FAILED;
            return new RunResult(
                    completion, supervision.exitCode(), startedAt, clock.instant(),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), process.pid(),
                    stdoutLog, stderrLog, supervision.termination());
        } finally {
            pumps.shutdownNow();
        }
    }

    private RunLog await(Future<RunLog> future, Process process, ProcessLimits limits)
            throws HarnessException {
        try {
            return future.get();
        } catch (InterruptedException failure) {
            try {
                if (process.isAlive()) {
                    supervisor.terminate(process, limits);
                }
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new HarnessException("HARNESS_RUN_INTERRUPTED", "Interrupted while waiting for external process logs", failure);
        } catch (ExecutionException failure) {
            if (process.isAlive()) {
                supervisor.terminate(process, limits);
            }
            Throwable cause = failure.getCause();
            if (cause instanceof HarnessException harness) {
                throw harness;
            }
            throw new HarnessException("HARNESS_LOG_CAPTURE_FAILED", "External process log archival failed", cause);
        }
    }
}
