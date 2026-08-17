package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetProjectAdapterContractTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldComposeCompleteBaselineAdapterFlowWithoutMutableProjectState() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.createDirectories(projectRoot.resolve("input"));
        Files.writeString(projectRoot.resolve("input/case.json"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(projectRoot.resolve("output"));
        Files.writeString(projectRoot.resolve("output/result.json"), "{\"makespan\":13}", StandardCharsets.UTF_8);

        TargetProjectAdapter<TextScheduleSnapshot> adapter = new FakeAdapter();
        TargetTest targetTest = new TargetTest("org.example.ScheduleTest", "case1");

        ProjectDescriptor project = adapter.inspect(projectRoot);
        TestLaunchSpec launchSpec = adapter.createLaunchSpec(project, targetTest, RunMode.BASELINE);
        Optional<Path> input = adapter.inputLocator().locate(project, targetTest);
        ScheduleResultSource resultSource = adapter.scheduleResultSource(project, targetTest);
        Path resultPath = resultSource.outputDirectory().resolve("result.json");
        TextScheduleSnapshot snapshot = adapter.scheduleResultParser().parse(resultPath);

        assertEquals("fake-adapter", adapter.descriptor().adapterId());
        assertEquals(project, launchSpec.project());
        assertEquals("org.example.ScheduleTest#case1", launchSpec.targetTest().selector());
        assertEquals(projectRoot.resolve("input/case.json"), input.orElseThrow());
        assertEquals(projectRoot.resolve("output"), resultSource.outputDirectory());
        assertEquals("{\"makespan\":13}", snapshot.payload());
        assertEquals(Set.of(
                        "descriptor", "inspect", "createLaunchSpec", "inputLocator",
                        "scheduleResultSource", "scheduleResultParser"),
                java.util.Arrays.stream(TargetProjectAdapter.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private record TextScheduleSnapshot(String schemaVersion, String payload)
            implements ScheduleResultSnapshot {
    }

    private static final class FakeAdapter implements TargetProjectAdapter<TextScheduleSnapshot> {

        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(
                    "fake-adapter",
                    "0.1.0",
                    "Test Fake Adapter",
                    Set.of(
                            AdapterCapability.BASELINE_EXECUTION,
                            AdapterCapability.INPUT_LOCATION,
                            AdapterCapability.SCHEDULE_RESULT));
        }

        @Override
        public ProjectDescriptor inspect(Path root) throws AdapterException {
            Path absoluteRoot = root.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absoluteRoot.resolve("pom.xml"))) {
                throw new AdapterException("ADAPTER_BUILD_FILE_MISSING", "pom.xml 不存在");
            }
            return new ProjectDescriptor(
                    new ProjectId("test-project"),
                    "Test Project",
                    absoluteRoot,
                    BuildTool.MAVEN,
                    Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project,
                TargetTest targetTest,
                RunMode runMode) {
            return new TestLaunchSpec(
                    project,
                    targetTest,
                    runMode,
                    List.of("test"),
                    Map.of("test", targetTest.selector()),
                    List.of(),
                    Duration.ofMinutes(2));
        }

        @Override
        public InputLocator inputLocator() {
            return (project, test) -> Optional.of(project.projectRoot().resolve("input/case.json"));
        }

        @Override
        public ScheduleResultSource scheduleResultSource(
                ProjectDescriptor project,
                TargetTest targetTest) {
            return new ScheduleResultSource(project.projectRoot().resolve("output"), false);
        }

        @Override
        public ScheduleResultParser<TextScheduleSnapshot> scheduleResultParser() {
            return path -> {
                try {
                    return new TextScheduleSnapshot("1.0", Files.readString(path));
                } catch (IOException exception) {
                    throw new AdapterException(
                            "ADAPTER_RESULT_PARSE_FAILED", "读取测试结果失败", exception);
                }
            };
        }

    }
}
