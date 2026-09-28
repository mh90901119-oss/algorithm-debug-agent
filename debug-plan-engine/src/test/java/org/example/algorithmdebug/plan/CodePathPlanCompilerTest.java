package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathProjectionSource;
import org.example.algorithmdebug.contracts.CodePathScalarType;
import org.example.algorithmdebug.contracts.CodePathScopeCondition;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;

class CodePathPlanCompilerTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void compilesStableExactSelectorsAcrossPackages() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.internal.Service", "solve", "(I)I", 1)));

        CodePathCollectionPlan plan = compile(catalog, request(List.of(
                "fixture.internal.Service#solve(I)I",
                "fixture.TargetTest#caseUnderTest()V")));

        assertEquals(List.of("fixture.TargetTest", "fixture.internal.Service"),
                plan.methodSelections().stream()
                        .map(selection -> selection.selector().className()).toList());
        assertEquals(List.of("()V", "(I)I"),
                plan.methodSelections().stream()
                        .map(selection -> selection.selector().descriptor()).toList());
        assertEquals(CollectionBudget.defaults(), plan.budget());
        assertEquals("Which methods executed?", plan.questionToAnswer());
    }

    @Test
    void rejectsUnknownDuplicateAndMoreThanFiftyMethods() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.TargetTest", "caseUnderTest", "()V", 0)));
        assertThrows(PlanCompilationException.class, () -> compile(
                catalog, request(List.of("fixture.Missing#run()V"))));
        assertThrows(PlanCompilationException.class, () -> compile(
                catalog, request(List.of(
                        "fixture.TargetTest#caseUnderTest()V",
                        "fixture.TargetTest#caseUnderTest()V"))));

        List<MethodCatalogEntry> entries = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> entry("fixture.TargetTest",
                        index == 0 ? "caseUnderTest" : "m" + index, "()V", index))
                .toList();
        assertThrows(PlanCompilationException.class, () -> compile(
                catalog(entries), request(entries.stream().map(MethodCatalogEntry::methodKey).toList())));
    }

    @Test
    void rejectsBlankOrOversizedRationaleAtTheRequestBoundary() {
        List<String> keys = List.of("fixture.TargetTest#caseUnderTest()V");

        assertThrows(IllegalArgumentException.class, () -> new CodePathPlanRequest(
                new PlanId("blank"), methods(keys.toArray(String[]::new)), Optional.empty(),
                " ", investigation(), CollectionBudget.defaults(), NOW));
        assertThrows(IllegalArgumentException.class, () -> new CodePathPlanRequest(
                new PlanId("oversized"), methods(keys.toArray(String[]::new)), Optional.empty(),
                "x".repeat(4_097), investigation(), CollectionBudget.defaults(), NOW));
    }

    @Test
    void validatesOptionalScopeAgainstCatalogAndSelectedMethods() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.Algorithm", "solve", "()V", 1)));
        String target = "fixture.TargetTest#caseUnderTest()V";
        String scope = "fixture.Algorithm#solve()V";

        CodePathCollectionPlan plan = compile(catalog,
                new CodePathPlanRequest(new PlanId("scope-plan"), methods(target, scope),
                        Optional.of(scope), "locate repeated paths",
                        investigation(), CollectionBudget.defaults(), NOW));

        assertEquals(Optional.of(scope), plan.scopeMethodKey());
        assertThrows(PlanCompilationException.class, () -> compile(
                catalog, new CodePathPlanRequest(new PlanId("not-selected"), methods(target),
                        Optional.of(scope), "invalid scope", investigation(),
                        CollectionBudget.defaults(), NOW)));
        assertThrows(PlanCompilationException.class, () -> compile(
                catalog, new CodePathPlanRequest(new PlanId("unknown"), methods(target),
                        Optional.of("fixture.Missing#run()V"), "invalid scope",
                        investigation(), CollectionBudget.defaults(), NOW)));
    }

    @Test
    void compilesScopeConditionsOnlyAgainstArgumentProjectionsOnTheScopeMethod() {
        String scope = "fixture.Algorithm#solve(Lfixture/Context;)V";
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.Algorithm", "solve", "(Lfixture/Context;)V", 1)));
        List<CodePathMethodRequest> methods = List.of(new CodePathMethodRequest(scope, List.of(
                new CodePathProjectionRequest("waferId", "arg[0].wafer.id", true))));

        CodePathCollectionPlan plan = compile(catalog,
                new CodePathPlanRequest(new PlanId("scoped"), methods, Optional.of(scope),
                        List.of(new CodePathScopeCondition(
                                "waferId", CodePathScalarType.STRING, "W-17")),
                        "isolate one repeated scheduling scope", investigation(),
                        CollectionBudget.defaults(), NOW));

        assertEquals("waferId", plan.scopeConditions().getFirst().projectionName());
        assertThrows(PlanCompilationException.class, () -> compile(
                catalog, new CodePathPlanRequest(new PlanId("unknown-projection"), methods,
                        Optional.of(scope), List.of(new CodePathScopeCondition(
                                "jobId", CodePathScalarType.STRING, "J-1")),
                        "invalid condition", investigation(), CollectionBudget.defaults(), NOW)));
    }

    @Test
    void compilesAggregateModeAndMatchedScopeOrdinalWindow() {
        String scope = "fixture.Algorithm#solve(Lfixture/Context;)V";
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.Algorithm", "solve", "(Lfixture/Context;)V", 1)));
        List<CodePathMethodRequest> methods = List.of(new CodePathMethodRequest(scope, List.of(
                new CodePathProjectionRequest("entity", "arg[0].id", true))));

        CodePathCollectionPlan plan = compile(catalog,
                new CodePathPlanRequest(new PlanId("aggregate-window"), methods,
                        Optional.of(scope), List.of(new CodePathScopeCondition(
                                "entity", CodePathScalarType.STRING, "E-17")),
                        CodePathCaptureMode.AGGREGATE, 100, 20,
                        "measure the selected path before tracing detail", investigation(),
                        CollectionBudget.defaults(), NOW));

        assertEquals(CodePathCaptureMode.AGGREGATE, plan.captureMode());
        assertEquals(100, plan.scopeStartOrdinal());
        assertEquals(20, plan.maxMatchedScopes());
    }

    @Test
    void rejectsScopeConditionsWithoutAScopeMethod() {
        String method = "fixture.Algorithm#solve(Lfixture/Context;)V";
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.Algorithm", "solve", "(Lfixture/Context;)V", 1)));
        List<CodePathMethodRequest> methods = List.of(new CodePathMethodRequest(method, List.of(
                new CodePathProjectionRequest("waferId", "arg[0].wafer.id", true))));

        assertThrows(PlanCompilationException.class, () -> compile(
                catalog, new CodePathPlanRequest(new PlanId("missing-scope"), methods,
                        Optional.empty(), List.of(new CodePathScopeCondition(
                                "waferId", CodePathScalarType.STRING, "W-17")),
                        "invalid condition", investigation(), CollectionBudget.defaults(), NOW)));
    }

    @Test
    void compilesArgumentAndReturnPathsIntoStructuredScalarProjections() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.internal.Service", "solve", "(Lfixture/Context;I)Lfixture/Result;", 1)));
        String methodKey = "fixture.internal.Service#solve(Lfixture/Context;I)Lfixture/Result;";
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("projection-plan"),
                List.of(new CodePathMethodRequest(methodKey, List.of(
                        new CodePathProjectionRequest("waferId", "arg[0].wafer.id", true),
                        new CodePathProjectionRequest("attempt", "arg[1]", false),
                        new CodePathProjectionRequest("selectedChamber", "return.chamber", false)))),
                Optional.empty(), "observe identities", investigation(), CollectionBudget.defaults(), NOW);

        CodePathCollectionPlan plan = compile(catalog, request);

        var projections = plan.methodSelections().getFirst().projections();
        assertEquals(3, projections.size());
        assertEquals(CodePathProjectionSource.ARGUMENT, projections.get(0).source());
        assertEquals(0, projections.get(0).argumentIndex().orElseThrow());
        assertEquals(List.of("wafer", "id"), projections.get(0).fieldPath());
        assertEquals(CodePathProjectionSource.RETURN, projections.get(2).source());
        assertEquals(List.of("chamber"), projections.get(2).fieldPath());
    }

    @Test
    void rejectsProjectionPathsThatDoNotMatchTheMethodDescriptor() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.internal.Service", "solve", "(I)V", 1)));
        String methodKey = "fixture.internal.Service#solve(I)V";

        assertProjectionRejected(catalog, methodKey, "arg[1]");
        assertProjectionRejected(catalog, methodKey, "return.value");
        assertProjectionRejected(catalog, methodKey, "arg[0].items[0]");
        assertProjectionRejected(catalog, methodKey, "arg[0].getId()");
    }

    @Test
    void rejectsDuplicateProjectionNamesWithinOneMethod() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.internal.Service", "solve", "(I)V", 1)));
        String methodKey = "fixture.internal.Service#solve(I)V";
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("duplicate-projection"),
                List.of(new CodePathMethodRequest(methodKey, List.of(
                        new CodePathProjectionRequest("value", "arg[0]", true),
                        new CodePathProjectionRequest("value", "arg[0]", false)))),
                Optional.empty(), "observe value", investigation(), CollectionBudget.defaults(), NOW);

        assertThrows(PlanCompilationException.class,
                () -> compile(catalog, request));
    }

    private CodePathPlanRequest request(List<String> keys) {
        return new CodePathPlanRequest(
                new PlanId("plan-1"), methods(keys.toArray(String[]::new)), Optional.empty(),
                "Locate the runtime path", investigation(), CollectionBudget.defaults(), NOW);
    }

    private List<CodePathMethodRequest> methods(String... keys) {
        return java.util.Arrays.stream(keys)
                .map(key -> new CodePathMethodRequest(key, List.of())).toList();
    }

    private InvestigationBindingRequest investigation() {
        return new InvestigationBindingRequest(
                "Which methods executed?", new EvidenceGapId("gap-1"),
                List.of(new HypothesisId("hypothesis-1")),
                List.of(new ObservationPredicateId("predicate-1")), List.of());
    }

    private void assertProjectionRejected(MethodCatalog catalog, String methodKey, String path) {
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("invalid-projection"),
                List.of(new CodePathMethodRequest(methodKey, List.of(
                        new CodePathProjectionRequest("value", path, true)))),
                Optional.empty(), "observe value", investigation(), CollectionBudget.defaults(), NOW);
        assertThrows(PlanCompilationException.class,
                () -> compile(catalog, request));
    }

    private CodePathCollectionPlan compile(
            MethodCatalog catalog, CodePathPlanRequest request) {
        return new CodePathPlanCompiler().compile(catalog, state(catalog), request);
    }

    private InvestigationState state(MethodCatalog catalog) {
        HypothesisId hypothesisId = new HypothesisId("hypothesis-1");
        EvidenceGapId gapId = new EvidenceGapId("gap-1");
        ObservationPredicateId predicateId = new ObservationPredicateId("predicate-1");
        SourceAnchor anchor = catalog.entries().getFirst().sourceAnchor();
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, hypothesisId,
                catalog.caseId(), catalog.analysisId(), "a candidate runtime path executed",
                HypothesisStatus.OPEN, List.of(anchor), List.of(), List.of(),
                List.of(gapId), NOW);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, gapId, catalog.caseId(), catalog.analysisId(),
                "which candidate path executed", EvidenceGapStatus.OPEN,
                List.of(hypothesisId), List.of(predicateId), NOW);
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, predicateId,
                catalog.caseId(), catalog.analysisId(), hypothesisId, gapId,
                ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches(), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"),
                catalog.caseId(), catalog.analysisId(), "unexpected algorithm result",
                "expected result", "actual result", catalog.targetTest(), List.of(anchor),
                List.of("run:run-1"), List.of("which path executed"), NOW);
        return new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE, catalog.caseId(), catalog.analysisId(),
                frame, List.of(hypothesis), List.of(gap), List.of(predicate),
                List.of(), 4, List.of());
    }

    private MethodCatalog catalog(List<MethodCatalogEntry> entries) {
        String targetKey = "fixture.TargetTest#caseUnderTest()V";
        List<MethodCatalogEntry> completeEntries = entries.stream()
                .anyMatch(entry -> entry.methodKey().equals(targetKey))
                ? entries
                : java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(entry(
                                "fixture.TargetTest", "caseUnderTest", "()V", 0)),
                        entries.stream()).toList();
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.TargetTest", "caseUnderTest"), completeEntries, List.of(), List.of(),
                SnapshotCompleteness.COMPLETE, completeEntries.size(), 0, NOW);
    }

    private MethodCatalogEntry entry(String className, String methodName, String descriptor, int distance) {
        String key = className + "#" + methodName + descriptor;
        return new MethodCatalogEntry(key, new SourceAnchor(
                className, methodName, descriptor, "src/test/java/fixture/TargetTest.java",
                1, 1), distance, distance == 0);
    }
}
