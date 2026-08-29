package org.example.algorithmdebug.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/** 启动一个立即返回、持续排空日志且由调用方显式拥有的外部进程。 */
public final class ManagedProcessRunner {
    private final ProcessSupervisor supervisor;
    private final BoundedOutputCapture output;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final ProcessStarter starter;

    /** 使用系统时钟和标准 ProcessBuilder 创建 Runner。 */
    public ManagedProcessRunner() {
        this(new ProcessSupervisor(), new BoundedOutputCapture(), Clock.systemUTC(),
                System::nanoTime, ProcessBuilder::start);
    }

    ManagedProcessRunner(
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

    /**
     * 启动进程但不等待退出。日志路径在启动前以 create-new 方式占用。
     *
     * @param observedMarkers 后续允许等待的有界 UTF-8 输出标记
     */
    public ManagedProcess start(
            List<String> argv,
            Path workingDirectory,
            Path stdoutPath,
            Path stderrPath,
            ProcessLimits limits,
            List<String> observedMarkers) throws HarnessException {
        if (argv == null || argv.isEmpty()
                || argv.stream().anyMatch(value -> value == null || value.isBlank())
                || workingDirectory == null || stdoutPath == null || stderrPath == null
                || limits == null) {
            throw new IllegalArgumentException("argv, working directory, and limits must be valid");
        }
        if (stdoutPath.toAbsolutePath().normalize().equals(
                stderrPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("stdoutPath and stderrPath must not reference the same file");
        }
        OutputMarkerRegistry markers = new OutputMarkerRegistry(observedMarkers);
        Path stdout = output.prepare(stdoutPath);
        Path stderr = output.prepare(stderrPath);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(List.copyOf(argv));
            builder.directory(workingDirectory.toAbsolutePath().normalize().toFile());
            process = starter.start(builder);
        } catch (IOException failure) {
            throw new HarnessException("HARNESS_PROCESS_START_FAILED", "Failed to start managed external process", failure);
        }
        ExecutorService pumps = Executors.newFixedThreadPool(2);
        Future<RunLog> stdoutFuture = pumps.submit(() -> output.capturePrepared(
                process.getInputStream(), stdout, limits.maximumStdoutBytes(), markers.observer(0)));
        Future<RunLog> stderrFuture = pumps.submit(() -> output.capturePrepared(
                process.getErrorStream(), stderr, limits.maximumStderrBytes(), markers.observer(1)));
        return new ManagedProcess(
                process, supervisor, limits, markers, pumps, stdoutFuture, stderrFuture,
                clock, nanoTime, clock.instant(), nanoTime.getAsLong());
    }
}
