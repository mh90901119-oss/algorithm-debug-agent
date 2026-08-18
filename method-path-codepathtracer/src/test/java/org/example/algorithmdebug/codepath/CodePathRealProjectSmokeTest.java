package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePathRealProjectSmokeTest {
    @TempDir Path collection;

    @Test
    void collectsActualWaferMethodPathWhenExternalFixturesAreConfigured() throws Exception {
        String moduleProperty = System.getProperty("ada.codepath.module");
        String jarProperty = System.getProperty("ada.codepath.jar");
        Assumptions.assumeTrue(moduleProperty != null && jarProperty != null);
        Path module = Path.of(moduleProperty).toAbsolutePath().normalize();
        Path jar = Path.of(jarProperty).toAbsolutePath().normalize();
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-smoke"),
                new CaseId("case-smoke"), new ContextId("ctx-smoke"),
                new AnalysisId("analysis-smoke"),
                new TargetTest("org.example.scheduler.wafer.SimpleWaferSchedulerTest",
                        "parallelModeAllowsJobsToAlternateOnSharedChamber"), "a".repeat(64),
                List.of(new MethodSelector(
                        "org.example.scheduler.wafer.SimpleWaferScheduler#schedule(Lorg/example/scheduler/wafer/WaferSchedulingInput;)Lorg/example/scheduler/wafer/WaferScheduleResult;",
                        "org.example.scheduler.wafer.SimpleWaferScheduler", "schedule",
                        "(Lorg/example/scheduler/wafer/WaferSchedulingInput;)Lorg/example/scheduler/wafer/WaferScheduleResult;",
                        "b".repeat(64))), List.of("org.example.scheduler.wafer"),
                "PACKAGE_SUPERSET", new CollectionBudget(100_000, 16L * 1024 * 1024, 120_000, 1_000),
                10_000, "smoke", Instant.EPOCH);
        List<String> classpath = new MavenTestClasspathResolver().resolve(
                Path.of(System.getProperty("ada.maven", "mvn")), module, collection);
        CodePathToolConfiguration tool = new CodePathToolConfiguration(
                java, jar, CodePathToolConfiguration.sha256(jar), "0.1.0-SNAPSHOT",
                "org.example.algorithmdebug.codepath.launcher.ExternalJUnitTraceLauncher");

        var result = new CodePathProcessCollector(tool).collect(new MethodPathCollectionRequest(
                plan.caseId(), plan.contextId(), plan.analysisId(), new RunId("run-smoke"), plan,
                new CollectionId("collection-smoke"), module, collection, java, classpath,
                plan.targetTest().selector()));

        assertTrue(result.manifest().rawEventCount() > 0);
        assertTrue(result.manifest().retainedEventCount() > 0);
    }
}
