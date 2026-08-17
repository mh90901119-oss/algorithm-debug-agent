package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaferDemoRealProjectSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "wafer.demo.projectRoot", matches = ".+")
    void shouldAdaptCurrentComplexFiveChamberResult() throws Exception {
        Path root = Path.of(System.getProperty("wafer.demo.projectRoot"));
        WaferDemoAdapter adapter = new WaferDemoAdapter();
        TargetTest test = new TargetTest(
                "org.example.scheduler.wafer.WaferSchedulingReproductionTest",
                "reproduceComplexSchedulingFromTimestampedInput");

        ProjectDescriptor project = adapter.inspect(root);
        Path input = adapter.inputLocator().locate(project, test).orElseThrow();
        Path outputDirectory = adapter.scheduleResultSource(project, test).outputDirectory();
        Path result;
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(outputDirectory)) {
            result = files.filter(java.nio.file.Files::isRegularFile)
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElseThrow();
        }
        WaferScheduleSnapshot snapshot = adapter.scheduleResultParser().parse(result);
        assertTrue(java.nio.file.Files.isRegularFile(input));
        assertEquals("20260810101501.json", input.getFileName().toString());
        assertEquals(165, snapshot.operations().size());
        assertEquals(15, snapshot.finalWaferLocations().size());
    }
}
