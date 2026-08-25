package org.example.algorithmdebug.jdwp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.harness.ManagedProcessRunner;
import org.example.algorithmdebug.harness.MavenExecutionOptions;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.RunCompletion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdwpCollectionCoordinatorTest {
    @TempDir
    Path directory;

    @Test
    void startsCollectorOnlyAfterTargetIsReadyAndReturnsBothSuccesses() throws Exception {
        List<String> order = new ArrayList<>();
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> { order.add("target"); return fixture("ready-exit", marker(port), "300", "0"); },
                (request, port) -> { order.add("collector"); return fixture("exit", "0"); });

        JdwpExecutionResult result = coordinator.execute(request(Duration.ofSeconds(2)));

        assertEquals(List.of("target", "collector"), order);
        assertEquals(JdwpCollectionCompletion.SUCCESS, result.completion());
        assertEquals(RunCompletion.SUCCEEDED, result.target().orElseThrow().completion());
        assertEquals(RunCompletion.SUCCEEDED, result.collector().orElseThrow().completion());
    }

    @Test
    void doesNotStartCollectorWhenTargetExitsBeforeReadiness() throws Exception {
        List<String> order = new ArrayList<>();
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> { order.add("target"); return fixture("exit", "2"); },
                (request, port) -> { order.add("collector"); return fixture("exit", "0"); });

        JdwpExecutionResult result = coordinator.execute(request(Duration.ofSeconds(2)));

        assertEquals(List.of("target"), order);
        assertEquals(JdwpCollectionCompletion.TARGET_FAILED, result.completion());
        assertFalse(result.collectorStarted());
    }

    @Test
    void readinessTimeoutCleansSuspendedTargetWithoutStartingCollector() throws Exception {
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> fixture("sleep"),
                (request, port) -> fixture("exit", "0"));

        JdwpExecutionResult result = coordinator.execute(request(Duration.ofMillis(80)));

        assertEquals(JdwpCollectionCompletion.TIMED_OUT, result.completion());
        assertEquals(RunCompletion.TIMED_OUT, result.target().orElseThrow().completion());
        assertFalse(result.collectorStarted());
        assertEquals(List.of(), result.target().orElseThrow().termination().survivingProcessIds());
    }

    @Test
    void collectorFailureCleansSuspendedTargetTree() throws Exception {
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> fixture("ready-sleep", marker(port)),
                (request, port) -> fixture("exit", "2"));

        JdwpExecutionResult result = coordinator.execute(request(Duration.ofSeconds(2)));

        assertEquals(JdwpCollectionCompletion.TOOL_FAILED, result.completion());
        assertEquals(RunCompletion.FAILED, result.collector().orElseThrow().completion());
        assertEquals(RunCompletion.TIMED_OUT, result.target().orElseThrow().completion());
        assertEquals(List.of(), result.target().orElseThrow().termination().survivingProcessIds());
    }

    @Test
    void classifiesTargetStartFailurePrecisely() throws Exception {
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> List.of(directory.resolve("missing-target.exe").toString()),
                (request, port) -> fixture("exit", "0"));

        JdwpAdapterException failure = assertThrows(
                JdwpAdapterException.class,
                () -> coordinator.execute(request(Duration.ofSeconds(2))));

        assertEquals("JDWP_TARGET_START_FAILED", failure.code());
        assertFalse(failure.targetStarted());
        assertFalse(failure.collectorStarted());
    }

    @Test
    void collectorStartFailureIsClassifiedAndCleansSuspendedTarget() throws Exception {
        Path pidFile = directory.resolve("target.pid");
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> fixture("ready-sleep-pid", pidFile.toString(), marker(port)),
                (request, port) -> List.of(directory.resolve("missing-collector.exe").toString()));

        JdwpAdapterException failure = assertThrows(
                JdwpAdapterException.class,
                () -> coordinator.execute(request(
                        Duration.ofSeconds(10), Duration.ofSeconds(12), 1_024 * 1_024)));

        assertEquals("JDWP_COLLECTOR_START_FAILED", failure.code());
        assertTrue(failure.targetStarted());
        assertFalse(failure.collectorStarted());
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void rawTraceLimitTerminatesBothProcessesAndMarksEvidenceTruncated() throws Exception {
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> fixture("ready-sleep", marker(port)),
                (request, port) -> fixture(
                        "write-sleep",
                        request.collectorOutputDirectory().resolve("raw-trace.jsonl").toString(),
                        "64"));

        JdwpExecutionResult result = coordinator.execute(
                request(Duration.ofSeconds(2), Duration.ofSeconds(3), 32));

        assertEquals(JdwpCollectionCompletion.TRUNCATED, result.completion());
        assertEquals(RunCompletion.TIMED_OUT, result.target().orElseThrow().completion());
        assertEquals(RunCompletion.TIMED_OUT, result.collector().orElseThrow().completion());
        assertEquals(List.of(), result.target().orElseThrow().termination().survivingProcessIds());
        assertEquals(List.of(), result.collector().orElseThrow().termination().survivingProcessIds());
    }

    @Test
    void overallTimeoutAfterCollectorSuccessStillCleansTarget() throws Exception {
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> fixture("ready-sleep", marker(port)),
                (request, port) -> fixture("exit", "0"));

        JdwpExecutionResult result = coordinator.execute(
                request(Duration.ofMillis(100), Duration.ofMillis(300), 1_024));

        assertEquals(JdwpCollectionCompletion.TIMED_OUT, result.completion());
        assertEquals(RunCompletion.TIMED_OUT, result.target().orElseThrow().completion());
        assertEquals(List.of(), result.target().orElseThrow().termination().survivingProcessIds());
    }

    @Test
    void targetFailureAfterCollectorStartedRemainsATargetFailure() throws Exception {
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> fixture("ready-exit", marker(port), "200", "2"),
                (request, port) -> fixture("exit", "0"));

        JdwpExecutionResult result = coordinator.execute(request(Duration.ofSeconds(2)));

        assertEquals(JdwpCollectionCompletion.TARGET_FAILED, result.completion());
        assertEquals(RunCompletion.FAILED, result.target().orElseThrow().completion());
        assertTrue(result.collectorStarted());
    }

    @Test
    void rejectsCollectorPlanWhoseEndpointDiffersFromExecutionPort() throws Exception {
        AtomicBoolean targetCommandRequested = new AtomicBoolean();
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> {
                    targetCommandRequested.set(true);
                    return fixture("exit", "0");
                },
                (request, port) -> fixture("exit", "0"));
        JdwpExecutionRequest request = request(Duration.ofSeconds(2));
        int mismatchedPort = request.port() == 60_000 ? 60_001 : 60_000;
        Files.writeString(
                request.collectorPlan(),
                "{\"target\":{\"host\":\"127.0.0.1\",\"port\":"
                        + mismatchedPort + "},\"resumeOnAttach\":true}");

        JdwpAdapterException failure = assertThrows(
                JdwpAdapterException.class, () -> coordinator.execute(request));

        assertEquals("JDWP_COLLECTOR_PLAN_ENDPOINT_MISMATCH", failure.code());
        assertFalse(targetCommandRequested.get());
    }

    @Test
    void interruptionPreservesFlagAndCleansBothProcesses() throws Exception {
        Path collectorPid = directory.resolve("collector.pid");
        JdwpCollectionCoordinator coordinator = coordinator(
                (request, port) -> fixture("ready-sleep", marker(port)),
                (request, port) -> fixture("write-pid-sleep", collectorPid.toString()));
        JdwpExecutionRequest request = request(Duration.ofSeconds(2));
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread execution = new Thread(() -> {
            try {
                coordinator.execute(request);
            } catch (Throwable failure) {
                observed.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        execution.start();
        awaitFile(collectorPid);

        execution.interrupt();
        execution.join(5_000);

        assertFalse(execution.isAlive());
        assertTrue(observed.get() instanceof JdwpAdapterException);
        assertTrue(interrupted.get());
        long pid = Long.parseLong(Files.readString(collectorPid));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private JdwpCollectionCoordinator coordinator(
            JdwpProcessCommandFactory target,
            JdwpProcessCommandFactory collector) {
        return new JdwpCollectionCoordinator(
                new ManagedProcessRunner(), target, collector,
                new LoopbackPortReadinessProbe(), System::nanoTime);
    }

    private JdwpExecutionRequest request(Duration readyTimeout) throws Exception {
        return request(readyTimeout, Duration.ofSeconds(3), 1_024 * 1_024);
    }

    private JdwpExecutionRequest request(
            Duration readyTimeout, Duration overallTimeout, long maximumRawBytes) throws Exception {
        Path java = Files.createFile(directory.resolve("java.exe"));
        Path jar = Files.createFile(directory.resolve("collector.jar"));
        int port = new LoopbackPortAllocator().allocate();
        Path plan = Files.writeString(
                directory.resolve("collector-plan.json"),
                "{\"target\":{\"host\":\"127.0.0.1\",\"port\":"
                        + port + "},\"resumeOnAttach\":true}");
        Path maven = Files.createFile(directory.resolve("mvn.cmd"));
        ProjectDescriptor project = new ProjectDescriptor(
                new ProjectId("demo"), "Demo", directory.toAbsolutePath(), BuildTool.MAVEN,
                Path.of("pom.xml"));
        TestLaunchSpec launch = new TestLaunchSpec(
                project, new TargetTest("org.example.ScheduleTest", "case1"), RunMode.JDWP,
                List.of("test"), Map.of(), List.of(), Duration.ofSeconds(2));
        MavenExecutionOptions targetOptions = new MavenExecutionOptions(
                maven, directory.resolve("target-out.log"), directory.resolve("target-err.log"),
                ProcessLimits.defaults());
        return new JdwpExecutionRequest(
                launch, targetOptions, port, java, jar,
                plan, directory.resolve("raw"),
                directory.resolve("collector-out.log"), directory.resolve("collector-err.log"),
                ProcessLimits.defaults(), maximumRawBytes, readyTimeout, overallTimeout);
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(file), "fixture 未在预算内写出 PID");
    }

    private static String marker(int port) {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port;
    }

    private static List<String> fixture(String... args) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CoordinatorFixtureMain.class.getName());
        command.addAll(List.of(args));
        return command;
    }
}
