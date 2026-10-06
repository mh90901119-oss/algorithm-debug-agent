package org.example.algorithmdebug.contracts.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;

class InvestigationContractsTest {
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void problemFrameRequiresSymptomExpectedActualTargetAndUnknown() {
        assertThrows(IllegalArgumentException.class, () -> new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"), CASE_ID, ANALYSIS_ID,
                "", "Expected", "Actual", new TargetTest("a.b.Test", "case1"),
                List.of(anchor()), List.of(), List.of("Unknown"), NOW));
        assertThrows(IllegalArgumentException.class, () -> new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"), CASE_ID, ANALYSIS_ID,
                "Symptom", "Expected", "Actual", new TargetTest("a.b.Test", "case1"),
                List.of(anchor()), List.of(), List.of(), NOW));
    }

    @Test
    void hypothesisCannotStartAsSupportedOrRefuted() {
        assertThrows(IllegalArgumentException.class, () -> hypothesis(HypothesisStatus.SUPPORTED));
        assertThrows(IllegalArgumentException.class, () -> hypothesis(HypothesisStatus.REFUTED));
        assertEquals(HypothesisStatus.OPEN, hypothesis(HypothesisStatus.OPEN).status());
    }

    @Test
    void predicateUnknownEffectIsAlwaysNoChange() {
        assertThrows(IllegalArgumentException.class, () -> predicate(
                HypothesisEffect.SUPPORT));
        assertEquals(HypothesisEffect.NO_CHANGE,
                predicate(HypothesisEffect.NO_CHANGE).onUnknown());
    }

    @Test
    void predicateUsesOnlySevenFixedOperators() {
        assertEquals(EnumSet.of(
                ObservationOperator.METHOD_OBSERVED,
                ObservationOperator.RECORD_EXISTS,
                ObservationOperator.VALUE_EQUALS,
                ObservationOperator.VALUE_CHANGED,
                ObservationOperator.COUNT_COMPARE,
                ObservationOperator.PATH_CONTAINS,
                ObservationOperator.FAILURE_FINGERPRINT_MATCHES),
                EnumSet.allOf(ObservationOperator.class));
    }

    @Test
    void sourceQueryUsesOnlySixFixedModes() {
        assertEquals(EnumSet.of(
                SourceQueryMode.METHOD,
                SourceQueryMode.CALLERS,
                SourceQueryMode.CALLEES,
                SourceQueryMode.REACHABLE_PATH,
                SourceQueryMode.SOURCE_WINDOW,
                SourceQueryMode.SEARCH_SYMBOL),
                EnumSet.allOf(SourceQueryMode.class));
    }

    @Test
    void planBindingRequiresOpenGapHypothesisAndOneToEightPredicates() {
        EvidenceGap openGap = gap(EvidenceGapStatus.OPEN);
        HypothesisRecord hypothesis = hypothesis(HypothesisStatus.OPEN);
        ObservationPredicate predicate = predicate(HypothesisEffect.NO_CHANGE);
        InvestigationBinding binding = InvestigationBinding.bind(
                openGap, List.of(hypothesis), List.of(predicate), List.of());
        assertEquals(openGap.gapId(), binding.gapId());

        assertThrows(IllegalArgumentException.class, () -> InvestigationBinding.bind(
                gap(EvidenceGapStatus.CLOSED), List.of(hypothesis), List.of(predicate), List.of()));
        assertThrows(IllegalArgumentException.class, () -> InvestigationBinding.bind(
                openGap, List.of(hypothesis), List.of(), List.of()));
        List<ObservationPredicate> tooMany = java.util.stream.IntStream.rangeClosed(
                        1, InvestigationLimits.MAX_PREDICATES_PER_BINDING + 1)
                .mapToObj(index -> predicate("predicate-" + index))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> InvestigationBinding.bind(
                openGap, List.of(hypothesis), tooMany, List.of()));
        ObservationPredicate corroborating = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE,
                new ObservationPredicateId("predicate-corroborating"), CASE_ID, ANALYSIS_ID,
                hypothesis.hypothesisId(), openGap.gapId(), ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("a.b.C#m()V"),
                PredicateRole.CORROBORATING, HypothesisEffect.SUPPORT,
                HypothesisEffect.NO_CHANGE, HypothesisEffect.NO_CHANGE, NOW);
        assertThrows(IllegalArgumentException.class, () -> InvestigationBinding.bind(
                openGap, List.of(hypothesis), List.of(corroborating), List.of()));

        HypothesisRecord unrelatedHypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD,
                hypothesis.hypothesisId(), CASE_ID, ANALYSIS_ID,
                hypothesis.statement(), hypothesis.status(), hypothesis.sourceAnchorRefs(),
                hypothesis.supportingEvaluationIds(), hypothesis.contradictingEvaluationIds(),
                List.of(new EvidenceGapId("gap-other")), NOW);
        assertThrows(IllegalArgumentException.class, () -> InvestigationBinding.bind(
                openGap, List.of(unrelatedHypothesis), List.of(predicate), List.of()));
    }

    @Test
    void causalEdgeRequiresEvidenceOrSourceReference() {
        assertThrows(IllegalArgumentException.class, () -> new CausalEdge(
                "edge-1", "node-1", "node-2", "causes",
                ClaimClassification.LLM_HYPOTHESIS, true, List.of(), List.of()));
        CausalEdge edge = new CausalEdge(
                "edge-1", "node-1", "node-2", "causes",
                ClaimClassification.VALIDATOR_CONCLUSION, true,
                List.of(new EvidenceId("evidence-1")), List.of());
        assertEquals(List.of(new EvidenceId("evidence-1")), edge.evidenceIds());
    }

    @Test
    void modelCommandsDoNotExposeSystemOwnedTransitions() {
        assertEquals(EnumSet.of(
                InvestigationUpdateCommand.CommandType.ADD_HYPOTHESIS,
                InvestigationUpdateCommand.CommandType.ADD_EVIDENCE_GAP,
                InvestigationUpdateCommand.CommandType.REGISTER_PREDICATE,
                InvestigationUpdateCommand.CommandType.MARK_GAP_UNRESOLVED),
                EnumSet.allOf(InvestigationUpdateCommand.CommandType.class));
    }

    @Test
    void sourceQueryRejectsRegexLikeSymbols() {
        ArtifactReference catalog = new ArtifactReference(
                "catalog-1", SourceQueryLimits.METHOD_CATALOG_ARTIFACT_TYPE,
                "analyses/analysis-1/static/method-catalog.json", "application/json",
                "a".repeat(InvestigationLimits.SHA256_HEX_LENGTH), 1);
        assertThrows(IllegalArgumentException.class, () -> new SourceQueryRequest(
                SchemaVersions.SOURCE_QUERY_REQUEST, new SourceQueryId("query-1"),
                CASE_ID, ANALYSIS_ID, catalog, SourceQueryMode.SEARCH_SYMBOL,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(".*"),
                SourceQueryBudget.defaults(), NOW));
    }

    @Test
    void sourceWindowAcceptsMultilineCodeButRejectsUnsafeControlCharacters() {
        SourceQueryResult.SourceWindow window = new SourceQueryResult.SourceWindow(
                anchor(), 10, 11, "a".repeat(InvestigationLimits.SHA256_HEX_LENGTH),
                "if (ready) {\n\treturn;\n}");

        assertEquals("if (ready) {\n\treturn;\n}", window.text());
        assertThrows(IllegalArgumentException.class, () -> new SourceQueryResult.SourceWindow(
                anchor(), 10, 10, "a".repeat(InvestigationLimits.SHA256_HEX_LENGTH),
                "unsafe\u0000code"));
    }

    private static HypothesisRecord hypothesis(HypothesisStatus status) {
        return new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD,
                new HypothesisId("hypothesis-1"), CASE_ID, ANALYSIS_ID,
                "The selected branch causes the observed order.", status,
                List.of(anchor()), List.of(), List.of(), List.of(new EvidenceGapId("gap-1")), NOW);
    }

    private static EvidenceGap gap(EvidenceGapStatus status) {
        return new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, new EvidenceGapId("gap-1"), CASE_ID, ANALYSIS_ID,
                "Which branch was selected?", status,
                List.of(new HypothesisId("hypothesis-1")), List.of(), NOW);
    }

    private static ObservationPredicate predicate(HypothesisEffect onUnknown) {
        return predicate("predicate-1", onUnknown);
    }

    private static ObservationPredicate predicate(String id) {
        return predicate(id, HypothesisEffect.NO_CHANGE);
    }

    private static ObservationPredicate predicate(String id, HypothesisEffect onUnknown) {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE,
                new ObservationPredicateId(id), CASE_ID, ANALYSIS_ID,
                new HypothesisId("hypothesis-1"), new EvidenceGapId("gap-1"),
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("a.b.C#m()V"),
                PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT,
                HypothesisEffect.REFUTE,
                onUnknown,
                NOW);
    }

    private static SourceAnchor anchor() {
        return new SourceAnchor("a.b.C", "m", "()V", "src/main/java/a/b/C.java", 10, 20);
    }
}
