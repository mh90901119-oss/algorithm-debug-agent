package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaferDemoAdapterTest {

    private static final String TEST_CLASS =
            "org.example.scheduler.wafer.WaferSchedulingReproductionTest";

    @TempDir
    Path projectRoot;

    private WaferDemoAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        WaferDemoTestProject.create(projectRoot);
        adapter = new WaferDemoAdapter();
    }

    @Test
    void shouldInspectProjectAndDeclareImplementedCapabilities() throws Exception {
        ProjectDescriptor project = adapter.inspect(projectRoot);

        assertEquals("wafer-scheduling-demo", project.projectId().value());
        assertEquals(projectRoot.toAbsolutePath().normalize(), project.projectRoot());
        assertTrue(adapter.descriptor().supports(AdapterCapability.BASELINE_EXECUTION));
        assertTrue(adapter.descriptor().supports(AdapterCapability.INPUT_LOCATION));
        assertTrue(adapter.descriptor().supports(AdapterCapability.SCHEDULE_RESULT));
        assertTrue(adapter.descriptor().supports(AdapterCapability.SEMANTIC_HASH));
    }

    @Test
    void shouldCreateStructuredLaunchSpecForComplexCase() throws Exception {
        ProjectDescriptor project = adapter.inspect(projectRoot);
        TargetTest test = target("reproduceComplexSchedulingFromTimestampedInput");

        TestLaunchSpec spec = adapter.createLaunchSpec(project, test, RunMode.BASELINE);

        assertEquals(List.of("test"), spec.mavenGoals());
        assertEquals(test.selector(), spec.mavenProperties().get("test"));
        assertEquals("true", spec.mavenProperties().get("failIfNoTests"));
        assertEquals(RunMode.BASELINE, spec.runMode());
    }

    @Test
    void shouldLocateReproductionInputAndDescribeDynamicResultSource() throws Exception {
        ProjectDescriptor project = adapter.inspect(projectRoot);

        TargetTest test = target("reproduceComplexSchedulingFromTimestampedInput");
        assertEquals(projectRoot.resolve("input/cases/20260810101501.json"),
                adapter.inputLocator().locate(project, test).orElseThrow());
        assertEquals(projectRoot.resolve("output/algorithm-results"),
                adapter.scheduleResultSource(project, test).outputDirectory());
    }

    @Test
    void shouldRejectUnknownTestInsteadOfGuessingPaths() throws Exception {
        ProjectDescriptor project = adapter.inspect(projectRoot);
        TargetTest unknown = target("unknownCase");

        AdapterException exception = assertThrows(AdapterException.class,
                () -> adapter.inputLocator().locate(project, unknown));

        assertEquals("ADAPTER_TEST_NOT_SUPPORTED", exception.code());
    }

    @Test
    void shouldRejectDirectoryThatIsNotWaferDemo() throws Exception {
        Path otherProject = projectRoot.resolve("other");
        java.nio.file.Files.createDirectories(otherProject);
        java.nio.file.Files.writeString(otherProject.resolve("pom.xml"), "<project/>");

        AdapterException exception = assertThrows(AdapterException.class,
                () -> adapter.inspect(otherProject));

        assertEquals("ADAPTER_PROJECT_NOT_SUPPORTED", exception.code());
    }

    @Test
    void missingInputDoesNotPreventProjectInspection() throws Exception {
        java.nio.file.Files.delete(
                projectRoot.resolve("input/cases/20260810101501.json"));

        ProjectDescriptor project = assertDoesNotThrow(() -> adapter.inspect(projectRoot));
        AdapterException failure = assertThrows(AdapterException.class,
                () -> adapter.inputLocator().locate(
                        project, target("reproduceComplexSchedulingFromTimestampedInput")));

        assertEquals("ADAPTER_INPUT_NOT_FOUND", failure.code());
    }

    private static TargetTest target(String method) {
        return new TargetTest(TEST_CLASS, method);
    }
}
