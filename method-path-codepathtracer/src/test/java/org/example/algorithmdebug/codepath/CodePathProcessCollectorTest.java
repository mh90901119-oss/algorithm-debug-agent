package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarOutputStream;
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
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePathProcessCollectorTest {
    @TempDir Path directory;

    @Test
    void startFailureArchivesPlanButDoesNotFabricateProcessState() throws Exception {
        CodePathToolConfiguration configuration = configuration(directory.resolve("missing-java"), createEmptyJar());

        MethodPathCollectionException failure = assertThrows(
                MethodPathCollectionException.class,
                () -> new CodePathProcessCollector(configuration).collect(request(1_000)));

        assertFalse(failure.processStarted());
        assertEquals(-1, failure.exitCode());
        assertTrue(Files.isRegularFile(directory.resolve("collection/request/plan.json")));
        assertFalse(Files.exists(directory.resolve("collection/request/plan.json.tmp")));
        assertTrue(Files.isRegularFile(directory.resolve("collection/logs/stdout.log")));
        assertTrue(Files.isRegularFile(directory.resolve("collection/logs/stderr.log")));
    }

    @Test
    void timeoutReturnsStructuredManifestAndSingleEmptyRawTrace() throws Exception {
        var result = new CodePathProcessCollector(configuration(javaExecutable(), createEmptyJar()))
                .collect(request(200));

        assertEquals(CollectionCompletion.TIMED_OUT, result.manifest().completion());
        assertTrue(result.manifest().processStarted());
        assertTrue(result.manifest().timedOut());
        assertNotEquals(0, result.manifest().exitCode());
        assertEquals(0, result.manifest().capturedEventCount());
        assertTrue(Files.isRegularFile(result.rawTrace()));
        assertEquals("raw/codepath.jsonl", result.manifest().rawTrace());
    }

    @Test
    void archivesSuccessfulAndZeroHitTargetFacts() throws Exception {
        var success = collector().collect(request(2_000, "success"));
        assertEquals(CollectionCompletion.SUCCESS, success.manifest().completion());
        assertEquals("PASSED", success.manifest().targetOutcome());
        assertEquals(1, success.manifest().testsSucceeded());
        assertEquals(1, success.manifest().capturedEventCount());

        Path second = Files.createDirectories(directory.resolve("zero-collection"));
        var zero = collector().collect(request(2_000, "zero-hit", second));
        assertEquals(CollectionCompletion.SUCCESS, zero.manifest().completion());
        assertEquals("PASSED", zero.manifest().targetOutcome());
        assertEquals(0, zero.manifest().capturedEventCount());
    }

    @Test
    void archivesTruncationAndTargetFailureSeparately() throws Exception {
        var truncated = collector().collect(request(2_000, "truncated"));
        assertEquals(CollectionCompletion.TRUNCATED, truncated.manifest().completion());
        assertEquals(List.of("launcher EVENTS"), truncated.manifest().truncationReasons());

        Path second = Files.createDirectories(directory.resolve("failed-collection"));
        var failed = collector().collect(request(2_000, "target-failed", second));
        assertEquals(CollectionCompletion.TARGET_FAILED, failed.manifest().completion());
        assertEquals("FAILED", failed.manifest().targetOutcome());
        assertEquals(1, failed.manifest().testsFailed());
    }

    @Test
    void preservesTargetFailureWhenCollectorAlsoReportsToolFailure() throws Exception {
        var result = collector().collect(request(2_000, "tool-and-target-failed"));

        assertEquals(CollectionCompletion.TOOL_FAILED, result.manifest().completion());
        assertEquals("FAILED", result.manifest().targetOutcome());
        assertEquals(1, result.manifest().testsFailed());
        assertEquals("CODEPATH_LAUNCHER_FAILED",
                result.manifest().agentFailure().orElseThrow().code());
    }

    @Test
    void malformedSummaryIsAStartedProcessProtocolFailure() throws Exception {
        MethodPathCollectionException failure = assertThrows(
                MethodPathCollectionException.class,
                () -> collector().collect(request(2_000, "malformed")));

        assertTrue(failure.processStarted());
        assertEquals(0, failure.exitCode());
        assertEquals("CODEPATH_LAUNCHER_PROTOCOL_INVALID", failure.code());
    }

    private CodePathProcessCollector collector() throws Exception {
        return new CodePathProcessCollector(configuration(javaExecutable(), createEmptyJar()));
    }

    private CodePathToolConfiguration configuration(Path java, Path jar) throws Exception {
        return new CodePathToolConfiguration(
                java, jar, CodePathToolConfiguration.sha256(jar), "test",
                CodePathProcessFixtureMain.class.getName());
    }

    private Path createEmptyJar() throws Exception {
        Path jar = directory.resolve("launcher.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // 测试入口来自显式目标 classpath，空 JAR 仅用于工具身份校验。
        }
        return jar;
    }

    private MethodPathCollectionRequest request(long timeoutMillis) throws Exception {
        Path collection = Files.createDirectories(directory.resolve("collection"));
        return request(timeoutMillis, "timeout", collection);
    }

    private MethodPathCollectionRequest request(long timeoutMillis, String rationale) throws Exception {
        Path collection = Files.createDirectories(directory.resolve("collection"));
        return request(timeoutMillis, rationale, collection);
    }

    private MethodPathCollectionRequest request(long timeoutMillis, String rationale, Path collection)
            throws Exception {
        CodePathCollectionPlan plan = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new TargetTest("fixture.TargetTest", "case1"),
                List.of(new MethodSelector("fixture.Service#solve()V", "fixture.Service", "solve", "()V")),
                new CollectionBudget(100, 1_024 * 1_024, timeoutMillis),
                rationale, Instant.EPOCH);
        return new MethodPathCollectionRequest(
                plan.caseId(), plan.contextId(), plan.analysisId(), new RunId("run-1"), plan,
                new CollectionId("collection-1"), directory, collection, javaExecutable(),
                Arrays.stream(System.getProperty("java.class.path").split(
                        java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                        .filter(value -> !value.isBlank()).toList(),
                plan.targetTest().selector());
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }
}
