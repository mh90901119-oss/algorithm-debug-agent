package org.example.algorithmdebug.jdwp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealJdwpCollectorSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOCKED_COLLECTOR_SHA256 =
            "be025dba387dd27264bcde2584118d8fbdf37f1df224e60df0f2fb4dcafdad78";

    @TempDir
    Path directory;

    @Test
    void lockedCollectorAttachesCapturesAndResumesMinimalTarget() throws Exception {
        String configured = System.getProperty("jdwp.collector.jar", "");
        Assumptions.assumeTrue(!configured.isBlank(),
                "需要 -Djdwp.collector.jar 运行真实 Collector Smoke");
        Path collectorJar = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(collectorJar), "Collector JAR 不存在");
        int port = new LoopbackPortAllocator().allocate();
        Path plan = Files.writeString(
                directory.resolve("collector-plan.json"), collectorPlan(port, tracepointLine()));
        Path java = javaExecutable();
        JdwpExecutionRequest request = request(java, collectorJar, plan, port);
        JdwpCollectionCoordinator coordinator = new JdwpCollectionCoordinator(
                new ManagedProcessRunner(),
                (ignored, actualPort) -> targetCommand(java, actualPort),
                (actual, actualPort) -> new JdwpCollectorCommandFactory().create(
                        actual.javaExecutable(), actual.collectorJar(), actual.collectorPlan(),
                        actual.collectorOutputDirectory(), actualPort),
                new LoopbackPortReadinessProbe(),
                System::nanoTime);

        JdwpExecutionResult result = coordinator.execute(request);

        assertEquals(JdwpCollectionCompletion.SUCCESS, result.completion());
        assertTrue(Files.size(request.rawTracePath()) > 0);
        List<JsonNode> events = Files.readAllLines(request.rawTracePath()).stream()
                .map(RealJdwpCollectorSmokeTest::readJson)
                .toList();
        assertTrue(events.stream().anyMatch(event ->
                "tracepoint_hit".equals(event.path("eventType").asText())
                        && "compute-entry".equals(event.path("tracepointId").asText())));
        assertTrue(Files.isRegularFile(
                request.collectorOutputDirectory().resolve("collection-manifest.json")));
    }

    private JdwpExecutionRequest request(
            Path java, Path collectorJar, Path plan, int port) throws Exception {
        Path projectRoot = directory.toAbsolutePath();
        ProjectDescriptor project = new ProjectDescriptor(
                new ProjectId("real-jdwp-smoke"), "Real JDWP Smoke", projectRoot,
                BuildTool.MAVEN, Path.of("pom.xml"));
        TestLaunchSpec launch = new TestLaunchSpec(
                project, new TargetTest(RealJdwpFixtureMain.class.getName(), "main"), RunMode.JDWP,
                List.of("test"), Map.of(), List.of(), Duration.ofSeconds(20));
        MavenExecutionOptions targetOptions = new MavenExecutionOptions(
                java, directory.resolve("target-out.log"), directory.resolve("target-err.log"),
                ProcessLimits.defaults());
        return new JdwpExecutionRequest(
                launch, targetOptions, port, java, collectorJar, LOCKED_COLLECTOR_SHA256,
                plan, directory.resolve("collector-raw"),
                directory.resolve("collector-out.log"), directory.resolve("collector-err.log"),
                ProcessLimits.defaults(), 4 * 1024 * 1024,
                Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    private static List<String> targetCommand(Path java, int port) {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RealJdwpFixtureMain.class.getName());
        return List.copyOf(command);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable)
                .toAbsolutePath().normalize();
    }

    private static int tracepointLine() throws Exception {
        Path source = Path.of("src/test/java/org/example/algorithmdebug/jdwp/RealJdwpFixtureMain.java");
        if (!Files.isRegularFile(source)) {
            source = Path.of("jdwp-collector-adapter").resolve(source);
        }
        List<String> lines = Files.readAllLines(source);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains("REAL_JDWP_TRACEPOINT")) {
                return index + 1;
            }
        }
        throw new IllegalStateException("找不到真实 JDWP Smoke tracepoint 行");
    }

    private static String collectorPlan(int port, int line) {
        return """
                {
                  "sessionId": "real-jdwp-smoke",
                  "target": {"host": "127.0.0.1", "port": %d},
                  "resumeOnAttach": true,
                  "idleTimeoutMillis": 5000,
                  "maxEvents": 10,
                  "tracepoints": [{
                    "id": "compute-entry",
                    "className": "%s",
                    "line": %d,
                    "methodName": "compute",
                    "maxHits": 1,
                    "capture": {
                      "locals": false,
                      "stack": true,
                      "maxFrames": 4,
                      "maxDepth": 1,
                      "maxItems": 10,
                      "maxStringLength": 128
                    }
                  }]
                }
                """.formatted(port, RealJdwpFixtureMain.class.getName(), line);
    }

    private static JsonNode readJson(String line) {
        try {
            return JSON.readTree(line);
        } catch (Exception failure) {
            throw new IllegalArgumentException("真实 Collector 输出了非法 JSONL", failure);
        }
    }
}
