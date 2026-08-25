package org.example.algorithmdebug.methodpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
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
import org.junit.jupiter.api.Test;

class MethodPathCollectionContractsTest {

    @Test
    void acceptsSingleRawTraceManifest() {
        MethodPathManifest manifest = successManifest(new CaseId("case-1"));

        assertEquals("raw/codepath.jsonl", manifest.rawTrace());
        assertEquals(10, manifest.capturedEventCount());
        assertEquals(100, manifest.capturedBytes());
    }

    @Test
    void rejectsResultPathOutsideCollectionDirectory() {
        Path root = Path.of("D:/workspace/collection").toAbsolutePath();
        MethodPathCollectionRequest request = request(root);

        assertThrows(IllegalArgumentException.class, () -> new MethodPathCollectionResult(
                request, successManifest(new CaseId("case-1")), root.resolve("../escape.jsonl"),
                root.resolve("logs/stdout.log"), root.resolve("logs/stderr.log")));
    }

    @Test
    void rejectsManifestWhoseIdentityDiffersFromRequest() {
        Path root = Path.of("D:/workspace/identity-collection").toAbsolutePath();
        MethodPathCollectionRequest request = request(root);

        assertThrows(IllegalArgumentException.class, () -> new MethodPathCollectionResult(
                request, successManifest(new CaseId("case-2")), root.resolve("raw/codepath.jsonl"),
                root.resolve("logs/stdout.log"), root.resolve("logs/stderr.log")));
    }

    @Test
    void structuredExceptionPreservesCodeAndCause() {
        IllegalStateException cause = new IllegalStateException("boom");
        MethodPathCollectionException failure = new MethodPathCollectionException(
                "METHOD_PATH_TOOL_FAILED", "collector failed", cause);
        assertEquals("METHOD_PATH_TOOL_FAILED", failure.code());
        assertEquals(cause, failure.getCause());
        assertEquals(false, failure.processStarted());
        assertEquals(-1, failure.exitCode());
    }

    @Test
    void failedManifestRequiresStructuredDiagnosticAndNoFabricatedExitCode() {
        assertThrows(IllegalArgumentException.class, () -> failureManifest(Optional.empty()));

        MethodPathManifest manifest = failureManifest(Optional.of(new AgentFailureDiagnostic(
                "CODEPATH_PLAN_ARCHIVE_FAILED", "plan archive failed")));

        assertEquals(CollectionCompletion.AGENT_FAILED, manifest.completion());
        assertEquals(false, manifest.processStarted());
        assertEquals(-1, manifest.exitCode());
    }

    private static MethodPathCollectionRequest request(Path root) {
        return new MethodPathCollectionRequest(
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new RunId("run-1"), plan(), new CollectionId("collection-1"),
                root, root, Path.of("java"), List.of("target/classes"), "fixture.Test#case1");
    }

    private static CodePathCollectionPlan plan() {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.Test", "case1"),
                List.of(new MethodSelector("fixture.Service#solve()V", "fixture.Service", "solve", "()V")),
                CollectionBudget.defaults(), "定位目标方法", Instant.EPOCH);
    }

    private static MethodPathManifest failureManifest(Optional<AgentFailureDiagnostic> diagnostic) {
        return new MethodPathManifest(
                "2.0", new CaseId("case-1"), new ContextId("ctx-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"), new PlanId("plan-1"),
                new CollectionId("collection-1"), "algorithm-debug-agent", "0.1.0",
                CollectionCompletion.AGENT_FAILED,
                "REQUEST_ARCHIVED", false, -1, false, "NOT_EXECUTED", 0, 0, 0, 0,
                0, 0,
                List.of(), diagnostic, "raw/codepath.jsonl", "logs/stdout.log", "logs/stderr.log",
                Instant.EPOCH, Instant.EPOCH);
    }

    private static MethodPathManifest successManifest(CaseId caseId) {
        return new MethodPathManifest(
                "2.0", caseId, new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new RunId("run-1"), new PlanId("plan-1"), new CollectionId("collection-1"),
                "code-path-tracer", "0.1.0",
                CollectionCompletion.SUCCESS, "COMPLETE", true, 0, false,
                "PASSED", 1, 1, 0, 0, 10, 100,
                List.of(), Optional.empty(),
                "raw/codepath.jsonl", "logs/stdout.log", "logs/stderr.log",
                Instant.EPOCH, Instant.EPOCH);
    }
}
