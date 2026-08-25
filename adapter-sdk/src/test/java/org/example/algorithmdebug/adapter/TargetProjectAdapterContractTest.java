package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetProjectAdapterContractTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldExposeOnlyProjectInspectionAndTestLaunchResponsibilities() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        TargetProjectAdapter adapter = new FakeAdapter();
        TargetTest targetTest = new TargetTest("org.example.ScheduleTest", "case1");

        ProjectDescriptor project = adapter.inspect(projectRoot);
        TestLaunchSpec launchSpec = adapter.createLaunchSpec(project, targetTest, RunMode.BASELINE);

        assertEquals("fake-adapter", adapter.descriptor().adapterId());
        assertEquals(project, launchSpec.project());
        assertEquals(targetTest.selector(), launchSpec.targetTest().selector());
        assertEquals(Set.of("descriptor", "inspect", "createLaunchSpec"),
                java.util.Arrays.stream(TargetProjectAdapter.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static final class FakeAdapter implements TargetProjectAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(
                    "fake-adapter", "0.1.0", "Test Fake Adapter",
                    Set.of(AdapterCapability.BASELINE_EXECUTION));
        }

        @Override
        public ProjectDescriptor inspect(Path root) throws AdapterException {
            Path absoluteRoot = root.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absoluteRoot.resolve("pom.xml"))) {
                throw new AdapterException("ADAPTER_BUILD_FILE_MISSING", "pom.xml 不存在");
            }
            return new ProjectDescriptor(
                    new ProjectId("test-project"), "Test Project", absoluteRoot,
                    BuildTool.MAVEN, Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest targetTest, RunMode runMode) {
            return new TestLaunchSpec(
                    project, targetTest, runMode, List.of("test"),
                    Map.of("test", targetTest.selector()), List.of(), Duration.ofMinutes(2));
        }
    }
}
