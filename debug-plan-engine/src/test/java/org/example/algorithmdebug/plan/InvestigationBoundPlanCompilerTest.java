package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathMethodSelection;
import org.example.algorithmdebug.contracts.CodePathProjection;
import org.example.algorithmdebug.contracts.CodePathProjectionSource;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationBindingStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestigationBoundPlanCompilerTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final HypothesisId HYPOTHESIS_ID = new HypothesisId("hypothesis-1");
    private static final EvidenceGapId GAP_ID = new EvidenceGapId("gap-1");
    private static final ObservationPredicateId PREDICATE_ID =
            new ObservationPredicateId("predicate-method");
    private static final String METHOD_KEY = "fixture.Algorithm#solve(I)I";

    @Test
    void compilesOnlyAgainstAnOpenGapExistingHypothesisAndFrozenPredicate() {
        var plan = new CodePathPlanCompiler().compile(catalog(), state(EvidenceGapStatus.OPEN,
                CASE_ID, ANALYSIS_ID, PredicateRole.CRITICAL,
                new ObservationSelector.MethodObserved(METHOD_KEY)), request(PREDICATE_ID));

        assertEquals(InvestigationBindingStatus.STRUCTURED, plan.investigationStatus());
        assertEquals("Did the algorithm method execute?", plan.questionToAnswer());
        assertEquals(GAP_ID, plan.investigationBinding().orElseThrow().gapId());
        assertEquals(List.of(PREDICATE_ID),
                plan.investigationBinding().orElseThrow().predicateIds());

        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(), state(EvidenceGapStatus.CLOSED, CASE_ID, ANALYSIS_ID,
                        PredicateRole.CRITICAL, new ObservationSelector.MethodObserved(METHOD_KEY)),
                request(PREDICATE_ID)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(), state(EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID,
                        PredicateRole.CRITICAL, new ObservationSelector.MethodObserved(METHOD_KEY)),
                request(new ObservationPredicateId("predicate-missing"))));
    }

    @Test
    void requiresAtLeastOneCriticalPredicateAndRejectsCrossAnalysisState() {
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(), state(EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID,
                        PredicateRole.CORROBORATING,
                        new ObservationSelector.MethodObserved(METHOD_KEY)), request(PREDICATE_ID)));

        AnalysisId other = new AnalysisId("analysis-2");
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(), state(EvidenceGapStatus.OPEN, CASE_ID, other,
                        PredicateRole.CRITICAL,
                        new ObservationSelector.MethodObserved(METHOD_KEY)), request(PREDICATE_ID)));
    }

    @Test
    void predicateMustBeComputableFromTheSelectedMethodAndProjection() {
        var unselectedMethod = state(EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID,
                PredicateRole.CRITICAL,
                new ObservationSelector.MethodObserved("fixture.Other#run()V"));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(), unselectedMethod, request(PREDICATE_ID)));

        var unselectedProjection = state(EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID,
                PredicateRole.CRITICAL,
                new ObservationSelector.ValueEquals(
                        "missingProjection", new ObservationSelector.LongValue(1)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(), unselectedProjection, request(PREDICATE_ID)));

        var orderedChange = state(EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID,
                PredicateRole.CRITICAL,
                new ObservationSelector.ValueChanged(
                        "decision", new ObservationSelector.LongValue(1),
                        new ObservationSelector.LongValue(2)));
        CodePathPlanRequest aggregate = new CodePathPlanRequest(
                new PlanId("aggregate-plan"), methods(), Optional.empty(), List.of(),
                CodePathCaptureMode.AGGREGATE, 1, 10_000, "inspect aggregate state",
                investigation(PREDICATE_ID), CollectionBudget.defaults(), NOW);
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(), orderedChange, aggregate));
    }

    @Test
    void recordSelectorsRequireARealNormalizedFieldAndProjectionNamesMustBeGloballyUnique() {
        InvestigationBindingValidator validator = new InvestigationBindingValidator();
        CodePathMethodSelection first = selection(METHOD_KEY, "decision");
        CodePathMethodSelection second = selection("fixture.Other#solve(I)I", "decision");

        InvestigationState unsupportedRecord = state(
                EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID, PredicateRole.CRITICAL,
                new ObservationSelector.RecordExists(
                        "CODEPATH_INVOCATION", "unknownField",
                        new ObservationSelector.TextValue("x")));
        assertThrows(PlanCompilationException.class, () -> validator.validateCodePath(
                unsupportedRecord, investigation(PREDICATE_ID), List.of(first),
                CodePathCaptureMode.TRACE));

        InvestigationState ambiguousProjection = state(
                EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID, PredicateRole.CRITICAL,
                new ObservationSelector.ValueEquals(
                        "decision", new ObservationSelector.LongValue(1)));
        assertThrows(PlanCompilationException.class, () -> validator.validateCodePath(
                ambiguousProjection, investigation(PREDICATE_ID), List.of(first, second),
                CodePathCaptureMode.TRACE));
    }

    @Test
    void duplicateBindingIdsAreRejectedAtTheRequestBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new InvestigationBindingRequest(
                "Did the algorithm method execute?", GAP_ID,
                List.of(HYPOTHESIS_ID, HYPOTHESIS_ID), List.of(PREDICATE_ID), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InvestigationBindingRequest(
                "Did the algorithm method execute?", GAP_ID,
                List.of(HYPOTHESIS_ID), List.of(PREDICATE_ID, PREDICATE_ID), List.of()));
    }

    @Test
    void jdwpRequiresCapturedRuntimeValuesAndRejectsMethodPathPredicates(
            @TempDir Path moduleRoot) throws Exception {
        Path source = moduleRoot.resolve("src/main/java/fixture/Algorithm.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; class Algorithm { int solve(int x) { return x; } }");
        JdwpTracepointRequest point = new JdwpTracepointRequest(
                "decision-point", METHOD_KEY, 1, 10, 2, 2, 0, List.of(),
                new JdwpCaptureSpec(true, 8, 256, List.of("candidate.id")));
        JdwpPlanRequest request = new JdwpPlanRequest(
                new PlanId("jdwp-plan"), List.of(point), JdwpCollectionBudget.defaults(),
                "inspect the decision value", investigation(PREDICATE_ID), NOW);
        InvestigationState valueState = state(
                EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID, PredicateRole.CRITICAL,
                new ObservationSelector.ValueEquals(
                        "candidate.id", new ObservationSelector.TextValue("A")));

        var plan = new JdwpPlanCompiler().compile(
                catalog(), valueState, request, moduleRoot);
        assertEquals(PREDICATE_ID,
                plan.investigationBinding().orElseThrow().predicateIds().getFirst());

        InvestigationState methodState = state(
                EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID, PredicateRole.CRITICAL,
                new ObservationSelector.MethodObserved(METHOD_KEY));
        assertThrows(PlanCompilationException.class, () -> new JdwpPlanCompiler().compile(
                catalog(), methodState, request, moduleRoot));

        JdwpTracepointRequest singleSnapshot = new JdwpTracepointRequest(
                "single", METHOD_KEY, 1, 10, 1, 1, 0, List.of(),
                new JdwpCaptureSpec(true, 8, 256, List.of("candidate.id")));
        JdwpPlanRequest changeRequest = new JdwpPlanRequest(
                new PlanId("jdwp-change"), List.of(singleSnapshot),
                JdwpCollectionBudget.defaults(), "inspect an ordered value change",
                investigation(PREDICATE_ID), NOW);
        InvestigationState changeState = state(
                EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID, PredicateRole.CRITICAL,
                new ObservationSelector.ValueChanged(
                        "candidate.id", new ObservationSelector.TextValue("A"),
                        new ObservationSelector.TextValue("B")));
        assertThrows(PlanCompilationException.class, () -> new JdwpPlanCompiler().compile(
                catalog(), changeState, changeRequest, moduleRoot));

        InvestigationState unsupportedRecord = state(
                EvidenceGapStatus.OPEN, CASE_ID, ANALYSIS_ID, PredicateRole.CRITICAL,
                new ObservationSelector.RecordExists(
                        "JDWP_SNAPSHOT", "missing.path",
                        new ObservationSelector.TextValue("A")));
        assertThrows(PlanCompilationException.class, () -> new JdwpPlanCompiler().compile(
                catalog(), unsupportedRecord, request, moduleRoot));
    }

    private static CodePathPlanRequest request(ObservationPredicateId predicateId) {
        return new CodePathPlanRequest(
                new PlanId("plan-1"), methods(), Optional.empty(), List.of(),
                CodePathCaptureMode.TRACE, 1, 10_000, "inspect the selected runtime path",
                investigation(predicateId), CollectionBudget.defaults(), NOW);
    }

    private static InvestigationBindingRequest investigation(ObservationPredicateId predicateId) {
        return new InvestigationBindingRequest(
                "Did the algorithm method execute?", GAP_ID,
                List.of(HYPOTHESIS_ID), List.of(predicateId),
                List.of(new EvidenceId("evidence-1")));
    }

    private static List<CodePathMethodRequest> methods() {
        return List.of(new CodePathMethodRequest(METHOD_KEY, List.of(
                new CodePathProjectionRequest("decision", "return", true))));
    }

    private static MethodCatalog catalog() {
        SourceAnchor anchor = anchor();
        SourceAnchor targetAnchor = new SourceAnchor(
                "fixture.AlgorithmTest", "solves", "()V",
                "src/test/java/fixture/AlgorithmTest.java", 1, 10);
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, CASE_ID, ANALYSIS_ID,
                new TargetTest("fixture.AlgorithmTest", "solves"),
                List.of(
                        new MethodCatalogEntry(
                                "fixture.AlgorithmTest#solves()V", targetAnchor, 0, true),
                        new MethodCatalogEntry(METHOD_KEY, anchor, 1, false)),
                List.of(), List.of(), SnapshotCompleteness.COMPLETE, 2, 0, NOW);
    }

    private static InvestigationState state(
            EvidenceGapStatus gapStatus,
            CaseId caseId,
            AnalysisId analysisId,
            PredicateRole role,
            ObservationSelector selector) {
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, HYPOTHESIS_ID, caseId, analysisId,
                "the selected algorithm method produced the decision", HypothesisStatus.OPEN,
                List.of(anchor()), List.of(), List.of(), List.of(GAP_ID), NOW);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, GAP_ID, caseId, analysisId,
                "which runtime path produced the decision", gapStatus,
                List.of(HYPOTHESIS_ID), List.of(PREDICATE_ID), NOW);
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, PREDICATE_ID, caseId, analysisId,
                HYPOTHESIS_ID, GAP_ID, selector.operator(), selector, role,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"), caseId, analysisId,
                "the algorithm selected an unexpected result", "result A", "result B",
                new TargetTest("fixture.AlgorithmTest", "solves"), List.of(anchor()),
                List.of("run:run-1"), List.of("which path executed"), NOW);
        return new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE, caseId, analysisId, frame,
                List.of(hypothesis), List.of(gap), List.of(predicate), List.of(), 4, List.of());
    }

    private static SourceAnchor anchor() {
        return new SourceAnchor(
                "fixture.Algorithm", "solve", "(I)I",
                "src/main/java/fixture/Algorithm.java", 1, 10);
    }

    private static CodePathMethodSelection selection(String methodKey, String projectionName) {
        String className = methodKey.substring(0, methodKey.indexOf('#'));
        return new CodePathMethodSelection(
                new MethodSelector(methodKey, className, "solve", "(I)I"),
                List.of(new CodePathProjection(
                        projectionName, CodePathProjectionSource.RETURN,
                        Optional.empty(), List.of(), true)));
    }
}
