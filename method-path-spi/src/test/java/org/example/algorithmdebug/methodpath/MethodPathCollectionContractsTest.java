package org.example.algorithmdebug.methodpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;

class MethodPathCollectionContractsTest {

    @Test
    void acceptsBoundedSuccessManifestAndDefensivelyCopiesPaths() {
        List<String> packages = new java.util.ArrayList<>(List.of("fixture"));
        MethodPathManifest manifest = new MethodPathManifest(
                "1.0", new CaseId("case-1"), new ContextId("ctx-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"), new PlanId("plan-1"),
                new CollectionId("collection-1"), "code-path-tracer", "f8be120",
                Optional.of("a".repeat(64)), "b".repeat(64), "PACKAGE_SUPERSET", "METHOD_ALLOWLIST",
                "CLASS_METHOD_SUPERSET", packages,
                CollectionCompletion.SUCCESS, "COMPLETE", true, 0, false, 10, 5, 0, 5,
                100, 50, Optional.of("c".repeat(64)), Optional.of("d".repeat(64)),
                List.of(), Optional.empty(), "logs/stdout.log", "logs/stderr.log",
                Instant.parse("2026-08-18T00:00:00Z"),
                Instant.parse("2026-08-18T00:00:01Z"));
        packages.clear();

        assertEquals(List.of("fixture"), manifest.packagePrefixes());
    }

    @Test
    void rejectsResultPathOutsideCollectionDirectory() {
        Path root = Path.of("D:/workspace/collection").toAbsolutePath();
        MethodPathCollectionRequest request = new MethodPathCollectionRequest(
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new RunId("run-1"), plan(), new CollectionId("collection-1"),
                root, root, Path.of("java"), List.of("target/classes"), "fixture.Test#case1");

        assertThrows(IllegalArgumentException.class, () -> new MethodPathCollectionResult(
                request, null, root.resolve("raw.jsonl"), root.resolve("../escape.jsonl"),
                root.resolve("stdout.log"), root.resolve("stderr.log")));
    }

    @Test
    void rejectsManifestWhoseCaseContextOrAnalysisIdentityDiffersFromRequest() {
        Path root = Path.of("D:/workspace/identity-collection").toAbsolutePath();
        MethodPathCollectionRequest request = new MethodPathCollectionRequest(
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new RunId("run-1"), plan(), new CollectionId("collection-1"),
                root, root, Path.of("java"), List.of("target/classes"), "fixture.Test#case1");
        MethodPathManifest wrongCase = successManifest(new CaseId("case-2"));

        assertThrows(IllegalArgumentException.class, () -> new MethodPathCollectionResult(
                request, wrongCase, root.resolve("raw.jsonl"), root.resolve("filtered.jsonl"),
                root.resolve("stdout.log"), root.resolve("stderr.log")));
    }

    private static CodePathCollectionPlan plan() {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.Test", "case1"), "a".repeat(64),
                List.of(new MethodSelector("fixture.Service#solve()V", "fixture.Service", "solve",
                        "()V", "b".repeat(64))), List.of("fixture"), "PACKAGE_SUPERSET",
                CollectionBudget.defaults(), 100, "定位", Instant.EPOCH);
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
    void agentFailedManifestRequiresStructuredAgentFailureAndNoFabricatedExitCode() {
        assertThrows(IllegalArgumentException.class, () -> failureManifest(Optional.empty()));

        MethodPathManifest manifest = failureManifest(Optional.of(new AgentFailureDiagnostic(
                "COLLECTION_SOURCE_DRIFT_BEFORE", "source changed")));

        assertEquals(CollectionCompletion.AGENT_FAILED, manifest.completion());
        assertEquals(false, manifest.processStarted());
        assertEquals(-1, manifest.exitCode());
    }

    private static MethodPathManifest failureManifest(
            Optional<AgentFailureDiagnostic> diagnostic) {
        return new MethodPathManifest(
                "1.0", new CaseId("case-1"), new ContextId("ctx-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"), new PlanId("plan-1"),
                new CollectionId("collection-1"), "algorithm-debug-agent", "0.1.0",
                Optional.empty(), "b".repeat(64), "PACKAGE_SUPERSET", "METHOD_ALLOWLIST",
                "NONE", List.of("fixture"), CollectionCompletion.AGENT_FAILED,
                "REQUEST_ARCHIVED", false, -1, false, 0, 0, 0, 0,
                0, 0, Optional.empty(), Optional.empty(), List.of(), diagnostic,
                "logs/stdout.log", "logs/stderr.log", Instant.EPOCH, Instant.EPOCH);
    }

    private static MethodPathManifest successManifest(CaseId caseId) {
        return new MethodPathManifest(
                "1.0", caseId, new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new RunId("run-1"), new PlanId("plan-1"), new CollectionId("collection-1"),
                "code-path-tracer", "0.1.0", Optional.of("a".repeat(64)), "b".repeat(64),
                "PACKAGE_SUPERSET", "METHOD_ALLOWLIST", "EXACT_DESCRIPTOR", List.of("fixture"),
                CollectionCompletion.SUCCESS, "COMPLETE", true, 0, false, 1, 1, 1, 0,
                100, 100, Optional.of("c".repeat(64)), Optional.of("d".repeat(64)),
                List.of(), Optional.empty(), "logs/stdout.log", "logs/stderr.log",
                Instant.EPOCH, Instant.EPOCH);
    }
}
