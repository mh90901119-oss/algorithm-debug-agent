package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleProducingTestRunnerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCaptureOnlyAfterSuccessfulStableRun() throws Exception {
        Path output = temporaryDirectory.resolve("output-success");
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        AtomicLong nanos = new AtomicLong();
        OutputStabilityWaiter waiter = waiter(snapshotter, nanos);
        TargetTestExecutor executor = (spec, options) -> {
            writeResult(output, "{\"schedule\":1}");
            return runResult(RunCompletion.SUCCEEDED, OptionalInt.of(0));
        };
        ScheduleProducingTestRunner<TextSnapshot> runner = new ScheduleProducingTestRunner<>(
                executor,
                snapshotter,
                waiter,
                new ScheduleResultCapture<>(snapshotter, 1024));

        ScheduleRunResult<TextSnapshot> result = runner.run(
                spec(), options(), source, this::parse,
                temporaryDirectory.resolve("run-success/result"));

        assertTrue(result.scheduleResult().isPresent());
        assertEquals(GanttOutcome.PRESENT, result.ganttOutcome());
        assertTrue(result.agentFailure().isEmpty());
        assertEquals(1, result.changedOutputCandidates().size());
        assertTrue(Files.isRegularFile(result.scheduleResult().orElseThrow().capturedPath()));
    }

    @Test
    void shouldCaptureStableOutputAfterFailedRun() throws Exception {
        Path output = temporaryDirectory.resolve("output-failed");
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        AtomicLong nanos = new AtomicLong();
        TargetTestExecutor executor = (spec, options) -> {
            writeResult(output, "{\"schedule\":\"stale-partial\"}");
            return runResult(RunCompletion.FAILED, OptionalInt.of(1));
        };
        ScheduleProducingTestRunner<TextSnapshot> runner = new ScheduleProducingTestRunner<>(
                executor,
                snapshotter,
                waiter(snapshotter, nanos),
                new ScheduleResultCapture<>(snapshotter, 1024));
        Path destination = temporaryDirectory.resolve("run-failed/result");

        ScheduleRunResult<TextSnapshot> result = runner.run(
                spec(), options(), source, this::parse, destination);

        assertTrue(result.scheduleResult().isPresent());
        assertEquals(GanttOutcome.PRESENT, result.ganttOutcome());
        assertTrue(result.agentFailure().isEmpty());
        assertEquals(1, result.changedOutputCandidates().size());
        assertTrue(Files.isRegularFile(destination.resolve("result.json")));
    }

    @Test
    void shouldRetainFailedRunWhenGanttCaptureFails() throws Exception {
        Path output = temporaryDirectory.resolve("output-invalid");
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        AtomicLong nanos = new AtomicLong();
        TargetTestExecutor executor = (spec, options) -> {
            writeResult(output, "not-a-schedule");
            return runResult(RunCompletion.FAILED, OptionalInt.of(1));
        };
        ScheduleProducingTestRunner<TextSnapshot> runner = new ScheduleProducingTestRunner<>(
                executor,
                snapshotter,
                waiter(snapshotter, nanos),
                new ScheduleResultCapture<>(snapshotter, 1024));

        ScheduleRunResult<TextSnapshot> result = runner.run(
                spec(), options(), source,
                path -> {
                    throw new AdapterException("TEST_INVALID_GANTT", "调度结果格式无效");
                },
                temporaryDirectory.resolve("run-invalid/result"));

        assertEquals(RunCompletion.FAILED, result.run().completion());
        assertEquals(1, result.run().exitCode().orElseThrow());
        assertEquals(GanttOutcome.INCOMPLETE, result.ganttOutcome());
        assertTrue(result.scheduleResult().isEmpty());
        assertEquals("HARNESS_RESULT_NOT_PRODUCED", result.agentFailure().orElseThrow().code());
        assertEquals(List.of(output.resolve("result.json")), result.changedOutputCandidates());
        assertThrows(UnsupportedOperationException.class,
                () -> result.changedOutputCandidates().clear());
    }

    @Test
    void shouldConvertUncheckedGanttFailureAfterRunIntoAgentDiagnostic() throws Exception {
        Path output = temporaryDirectory.resolve("output-unchecked");
        ScheduleResultSource source = new ScheduleResultSource(output, false);
        OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(100);
        AtomicLong nanos = new AtomicLong();
        TargetTestExecutor executor = (spec, options) -> {
            writeResult(output, "{\"schedule\":1}");
            return runResult(RunCompletion.SUCCEEDED, OptionalInt.of(0));
        };
        ScheduleProducingTestRunner<TextSnapshot> runner = new ScheduleProducingTestRunner<>(
                executor,
                snapshotter,
                waiter(snapshotter, nanos),
                new ScheduleResultCapture<>(snapshotter, 1024));

        IllegalStateException parserFailure = new IllegalStateException("unexpected parser failure");
        ScheduleRunResult<TextSnapshot> result = runner.run(
                spec(), options(), source,
                path -> {
                    throw parserFailure;
                },
                temporaryDirectory.resolve("run-unchecked/result"));

        assertEquals(RunCompletion.SUCCEEDED, result.run().completion());
        assertEquals(GanttOutcome.INCOMPLETE, result.ganttOutcome());
        assertEquals("HARNESS_GANTT_PROCESSING_FAILED", result.agentFailure().orElseThrow().code());
        assertEquals("java.lang.IllegalStateException",
                result.agentFailure().orElseThrow().exceptionClass());
        assertEquals(parserFailure, result.agentFailureCause().orElseThrow());
        assertEquals(List.of(output.resolve("result.json")), result.changedOutputCandidates());
    }

    private OutputStabilityWaiter waiter(OutputDirectorySnapshotter snapshotter, AtomicLong nanos) {
        return new OutputStabilityWaiter(
                new OutputStabilityPolicy(Duration.ofMillis(1), Duration.ofSeconds(1), 2),
                snapshotter::snapshot,
                duration -> nanos.addAndGet(duration.toNanos()),
                nanos::get);
    }

    private TestLaunchSpec spec() {
        ProjectDescriptor project = new ProjectDescriptor(
                new ProjectId("demo"), "Demo", temporaryDirectory.toAbsolutePath(),
                BuildTool.MAVEN, Path.of("pom.xml"));
        return new TestLaunchSpec(
                project,
                new TargetTest("org.example.ScheduleTest", "case1"),
                RunMode.BASELINE,
                List.of("test"), Map.of(), List.of(), Duration.ofSeconds(1));
    }

    private MavenExecutionOptions options() throws Exception {
        Path executable = temporaryDirectory.resolve("mvn.cmd");
        if (!Files.exists(executable)) {
            Files.createFile(executable);
        }
        return new MavenExecutionOptions(
                executable,
                temporaryDirectory.resolve("stdout.log"),
                temporaryDirectory.resolve("stderr.log"),
                ProcessLimits.defaults());
    }

    private TextSnapshot parse(Path path) throws AdapterException {
        try {
            return new TextSnapshot("1.0", Files.readString(path));
        } catch (java.io.IOException exception) {
            throw new AdapterException("TEST_IO", "读取失败", exception);
        }
    }

    private static void writeResult(Path output, String content) throws HarnessException {
        try {
            Files.createDirectories(output);
            Files.writeString(output.resolve("result.json"), content);
        } catch (java.io.IOException exception) {
            throw new HarnessException("TEST_WRITE_FAILED", "无法创建测试结果", exception);
        }
    }

    private RunResult runResult(RunCompletion completion, OptionalInt exitCode) {
        Instant started = Instant.parse("2026-08-11T00:00:00Z");
        return new RunResult(
                completion,
                exitCode,
                started,
                started.plusSeconds(1),
                Duration.ofSeconds(1),
                42,
                new RunLog(temporaryDirectory.resolve("fake-out.log"), 0, 0, false),
                new RunLog(temporaryDirectory.resolve("fake-err.log"), 0, 0, false),
                TerminationReport.notAttempted());
    }

    private record TextSnapshot(String schemaVersion, String value)
            implements ScheduleResultSnapshot {
    }
}
