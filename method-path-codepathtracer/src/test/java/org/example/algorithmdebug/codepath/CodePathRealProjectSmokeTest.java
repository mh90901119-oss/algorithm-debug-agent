package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.example.algorithmdebug.methodpath.MethodPathCollectionResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePathRealProjectSmokeTest {
    private static final String DESCRIPTOR =
            "(Lorg/example/scheduler/wafer/WaferSchedulingInput;)"
                    + "Lorg/example/scheduler/wafer/WaferScheduleResult;";
    @TempDir Path collection;

    @Test
    void collectsOnlySelectedWaferMethodWhenExternalFixturesAreConfigured() throws Exception {
        MethodPathCollectionResult result = collect(
                CodePathCaptureMode.TRACE, Files.createDirectories(collection.resolve("trace")));

        assertTrue(result.manifest().capturedEventCount() > 0);
        assertEquals("PASSED", result.manifest().targetOutcome());
        assertEquals(1, result.manifest().testsSucceeded());
        ObjectMapper json = new ObjectMapper();
        long lineCount;
        try (var lines = Files.lines(result.rawTrace())) {
            var rawLines = lines.filter(line -> !line.isBlank()).toList();
            lineCount = rawLines.size();
            rawLines.forEach(line -> {
                try {
                    var event = json.readTree(line);
                    assertEquals("org.example.scheduler.wafer.SimpleWaferScheduler",
                            event.path("className").asText());
                    assertEquals("schedule", event.path("methodName").asText());
                    assertEquals(DESCRIPTOR, event.path("descriptor").asText());
                } catch (java.io.IOException failure) {
                    throw new AssertionError("Raw CodePath line must be valid JSON", failure);
                }
            });
        }
        assertEquals(result.manifest().capturedEventCount(), lineCount);
        assertEquals(result.manifest().capturedBytes(), Files.size(result.rawTrace()));
        System.out.printf("CODEPATH_REAL_SMOKE mode=TRACE events=%d bytes=%d%n",
                lineCount, result.manifest().capturedBytes());
    }

    @Test
    void aggregatesOnlySelectedWaferMethodWhenExternalFixturesAreConfigured() throws Exception {
        MethodPathCollectionResult result = collect(
                CodePathCaptureMode.AGGREGATE,
                Files.createDirectories(collection.resolve("aggregate")));

        var aggregate = new ObjectMapper().readTree(result.rawTrace().toFile());
        assertEquals("PASSED", result.manifest().targetOutcome());
        assertEquals("AGGREGATE", aggregate.path("captureMode").asText());
        assertEquals("SELECTED_METHOD_DEPTH", aggregate.path("depthSemantics").asText());
        assertEquals(1, aggregate.path("methods").size());
        assertEquals("org.example.scheduler.wafer.SimpleWaferScheduler#schedule" + DESCRIPTOR,
                aggregate.path("methods").get(0).path("methodKey").asText());
        assertTrue(aggregate.path("methods").get(0).path("enterCount").asLong() > 0);
        assertEquals(result.manifest().capturedBytes(), Files.size(result.rawTrace()));
        System.out.printf("CODEPATH_REAL_SMOKE mode=AGGREGATE events=%d bytes=%d%n",
                result.manifest().capturedEventCount(), result.manifest().capturedBytes());
    }

    private MethodPathCollectionResult collect(CodePathCaptureMode captureMode, Path output)
            throws Exception {
        String moduleProperty = System.getProperty("ada.codepath.module");
        String jarProperty = System.getProperty("ada.codepath.jar");
        Assumptions.assumeTrue(moduleProperty != null && jarProperty != null);
        Path module = Path.of(moduleProperty).toAbsolutePath().normalize();
        Path jar = Path.of(jarProperty).toAbsolutePath().normalize();
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN_LEGACY, new PlanId("plan-smoke"),
                new CaseId("case-smoke"), new AnalysisId("analysis-smoke"),
                new TargetTest("org.example.scheduler.wafer.SimpleWaferSchedulerTest",
                        "parallelModeAllowsJobsToAlternateOnSharedChamber"),
                List.of(new org.example.algorithmdebug.contracts.CodePathMethodSelection(
                        new MethodSelector(
                                "org.example.scheduler.wafer.SimpleWaferScheduler#schedule" + DESCRIPTOR,
                                "org.example.scheduler.wafer.SimpleWaferScheduler", "schedule", DESCRIPTOR),
                        List.of())),
                Optional.empty(), List.of(), captureMode, 1, 10_000,
                new CollectionBudget(100_000, 16L * 1024 * 1024, 120_000),
                "smoke", new org.example.algorithmdebug.contracts.InvestigationIntent(
                        "Which path executed?", "The scheduler method executed", List.of(),
                        List.of("Observed method path")), Instant.EPOCH);
        List<String> classpath = new MavenTestClasspathResolver().resolve(
                Path.of(System.getProperty("ada.maven", "mvn")), module, output);
        CodePathToolConfiguration tool = new CodePathToolConfiguration(
                java, jar, "0.1.0-SNAPSHOT",
                "org.example.algorithmdebug.codepath.launcher.ExternalJUnitTraceLauncher");

        return new CodePathProcessCollector(tool).collect(new MethodPathCollectionRequest(
                plan.caseId(), plan.analysisId(), new RunId("run-smoke"), plan,
                new CollectionId("collection-smoke"), module, output, java, classpath,
                plan.targetTest().selector()));
    }
}
