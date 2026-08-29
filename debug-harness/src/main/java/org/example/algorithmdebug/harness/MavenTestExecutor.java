package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.TestLaunchSpec;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/** 使用 Maven/Surefire 在受监管子进程中执行一个结构化目标 UT。 */
public final class MavenTestExecutor implements TargetTestExecutor {

    private final MavenCommandFactory commandFactory;
    private final ProcessSupervisor supervisor;
    private final BoundedOutputCapture outputCapture;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final ProcessStarter processStarter;

    /** 使用系统时钟和默认确定性组件创建执行器。 */
    public MavenTestExecutor() {
        this(new MavenCommandFactory(), new ProcessSupervisor(), new BoundedOutputCapture(),
                Clock.systemUTC(), System::nanoTime, ProcessBuilder::start);
    }

    MavenTestExecutor(
            MavenCommandFactory commandFactory,
            ProcessSupervisor supervisor,
            BoundedOutputCapture outputCapture,
            Clock clock,
            LongSupplier nanoTime,
            ProcessStarter processStarter) {
        this.commandFactory = commandFactory;
        this.supervisor = supervisor;
        this.outputCapture = outputCapture;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.processStarter = processStarter;
    }

    /**
     * @return 成功、测试失败或超时的结构化运行结果
     * @throws HarnessException 无法启动、捕获日志、中断或清理失败
     */
    @Override
    public RunResult execute(TestLaunchSpec spec, MavenExecutionOptions options)
            throws HarnessException {
        if (spec == null || options == null) {
            throw new IllegalArgumentException("spec and options must not be null");
        }
        Instant startedAt = clock.instant();
        long startedNanos = nanoTime.getAsLong();
        PathPair logs = prepareLogs(options);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(commandFactory.create(spec, options));
            builder.directory(spec.project().projectRoot().toFile());
            process = processStarter.start(builder);
        } catch (IOException exception) {
            throw new HarnessException(
                    "HARNESS_PROCESS_START_FAILED",
                    "Failed to start target Maven process, test: " + spec.targetTest().selector(),
                    exception);
        }

        ExecutorService pumps = Executors.newFixedThreadPool(2);
        Future<RunLog> stdoutFuture = pumps.submit(() -> outputCapture.capturePrepared(
                process.getInputStream(), logs.stdout(), options.processLimits().maximumStdoutBytes()));
        Future<RunLog> stderrFuture = pumps.submit(() -> outputCapture.capturePrepared(
                process.getErrorStream(), logs.stderr(), options.processLimits().maximumStderrBytes()));
        try {
            SupervisionResult supervision = supervisor.await(process, spec.timeout(), options.processLimits());
            RunLog stdout = awaitLog(stdoutFuture, process, options.processLimits());
            RunLog stderr = awaitLog(stderrFuture, process, options.processLimits());
            Instant finishedAt = clock.instant();
            Duration elapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startedNanos));
            RunCompletion completion = supervision.timedOut()
                    ? RunCompletion.TIMED_OUT
                    : supervision.exitCode().orElseThrow() == 0
                            ? RunCompletion.SUCCEEDED
                            : RunCompletion.FAILED;
            OptionalInt exitCode = supervision.exitCode();
            return new RunResult(
                    completion,
                    exitCode,
                    startedAt,
                    finishedAt,
                    elapsed,
                    process.pid(),
                    stdout,
                    stderr,
                    supervision.termination());
        } finally {
            pumps.shutdownNow();
        }
    }

    private PathPair prepareLogs(MavenExecutionOptions options) throws HarnessException {
        java.nio.file.Path stdout = outputCapture.prepare(options.stdoutLog());
        java.nio.file.Path stderr = outputCapture.prepare(options.stderrLog());
        return new PathPair(stdout, stderr);
    }

    private RunLog awaitLog(
            Future<RunLog> future,
            Process process,
            ProcessLimits limits) throws HarnessException {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            try {
                if (process.isAlive()) {
                    supervisor.terminate(process, limits);
                }
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new HarnessException("HARNESS_RUN_INTERRUPTED", "Interrupted while waiting for log archival", exception);
        } catch (ExecutionException exception) {
            if (process.isAlive()) {
                supervisor.terminate(process, limits);
            }
            Throwable cause = exception.getCause();
            if (cause instanceof HarnessException harnessException) {
                throw harnessException;
            }
            throw new HarnessException("HARNESS_LOG_CAPTURE_FAILED", "The log archival task failed", cause);
        }
    }

    private record PathPair(java.nio.file.Path stdout, java.nio.file.Path stderr) {
    }
}
