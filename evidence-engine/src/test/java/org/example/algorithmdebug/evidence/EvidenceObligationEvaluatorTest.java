package org.example.algorithmdebug.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.coordination.EvidenceObligationKind;
import org.example.algorithmdebug.contracts.coordination.ObligationStatus;
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
import org.junit.jupiter.api.Test;

class EvidenceObligationEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final ProjectId PROJECT_ID = new ProjectId("project-obligation");
    private static final CaseId CASE_ID = new CaseId("case-obligation");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-obligation");
    private static final AnalysisIdentity IDENTITY =
            new AnalysisIdentity(PROJECT_ID, CASE_ID, ANALYSIS_ID);
    private static final HypothesisId HYPOTHESIS_ID = new HypothesisId("hypothesis-obligation");
    private static final EvidenceGapId GAP_ID = new EvidenceGapId("gap-obligation");
    private static final ObservationPredicateId PREDICATE_ID =
            new ObservationPredicateId("predicate-obligation");
    private static final SourceAnchor ANCHOR = new SourceAnchor(
            "fixture.Algorithm", "solve", "()V", "src/main/java/fixture/Algorithm.java", 1, 10);

    private final EvidenceObligationEvaluator evaluator = new EvidenceObligationEvaluator();

    @Test
    void systemToolAndInvestigationObligationsRemainIndependent() {
        ObservationPredicate predicate = predicate();
        InvestigationState state = state(predicate, unknownEvaluation(predicate));
        AnalysisControlView control = control(List.of("tool-plan-bound"), List.of());

        var obligations = evaluator.evaluate(control, state);

        assertTrue(obligations.stream().anyMatch(value ->
                value.kind() == EvidenceObligationKind.SYSTEM_PREREQUISITE
                        && value.status() == ObligationStatus.SATISFIED));
        assertTrue(obligations.stream().anyMatch(value ->
                value.kind() == EvidenceObligationKind.ACTION_POSTCONDITION
                        && value.referenceIds().contains("tool-plan-bound")
                        && value.status() == ObligationStatus.SATISFIED));
        assertTrue(obligations.stream().anyMatch(value ->
                value.kind() == EvidenceObligationKind.INVESTIGATION_PREDICATE
                        && value.referenceIds().contains(PREDICATE_ID.value())
                        && value.status() == ObligationStatus.UNCHECKABLE));
    }

    @Test
    void satisfiedToolObligationCannotCloseUnknownCriticalPredicate() {
        ObservationPredicate predicate = predicate();
        InvestigationState state = state(predicate, unknownEvaluation(predicate));

        var obligations = evaluator.evaluate(
                control(List.of("tool-plan-bound"), List.of()), state);

        assertEquals(ObligationStatus.SATISFIED, obligations.stream()
                .filter(value -> value.kind() == EvidenceObligationKind.ACTION_POSTCONDITION)
                .findFirst().orElseThrow().status());
        assertEquals(ObligationStatus.UNCHECKABLE, obligations.stream()
                .filter(value -> value.kind() == EvidenceObligationKind.INVESTIGATION_PREDICATE)
                .findFirst().orElseThrow().status());
    }

    @Test
    void missingToolAndContradictedPredicateRemainSeparateObligations() {
        ObservationPredicate predicate = predicate();
        EvidenceView evidence = new EvidenceView(
                CASE_ID, ANALYSIS_ID, List.of(new EvidenceId("evidence-contradiction")),
                EvidenceSourceCoverage.COMPLETE, ObservationEvaluatorTest.eligible(),
                ComparisonOutcome.NOT_COMPARED,
                List.of("fixture.Algorithm#solve()V"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), NOW);
        var contradiction = new ObservationEvaluator().evaluate(predicate, evidence);

        var obligations = evaluator.evaluate(
                control(List.of(), List.of("tool-plan-bound")),
                state(predicate, contradiction));

        assertTrue(obligations.stream().anyMatch(value ->
                value.kind() == EvidenceObligationKind.ACTION_POSTCONDITION
                        && value.status() == ObligationStatus.MISSING));
        assertTrue(obligations.stream().anyMatch(value ->
                value.kind() == EvidenceObligationKind.INVESTIGATION_PREDICATE
                        && value.status() == ObligationStatus.CONTRADICTED));
    }

    @Test
    void identityMismatchIsRejectedInsteadOfProducingCrossAnalysisObligations() {
        AnalysisIdentity other = new AnalysisIdentity(
                PROJECT_ID, CASE_ID, new AnalysisId("analysis-other"));
        AnalysisControlView control = new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW, CoordinationPolicyVersions.CURRENT,
                other, 1, AnalysisActionType.ANALYSIS_STATUS, ActionDecisionCode.ALLOWED,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(AnalysisActionType.ANALYSIS_STATUS),
                ConclusionStatus.MISSING_EVIDENCE);

        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(control, state(predicate(), unknownEvaluation(predicate()))));
    }

    private static ObservationPredicate predicate() {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, PREDICATE_ID, CASE_ID, ANALYSIS_ID,
                HYPOTHESIS_ID, GAP_ID, ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Algorithm#solve()V"),
                PredicateRole.CRITICAL, HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
    }

    private static org.example.algorithmdebug.contracts.investigation.ObservationEvaluation
            unknownEvaluation(ObservationPredicate predicate) {
        EvidenceView evidence = new EvidenceView(
                CASE_ID, ANALYSIS_ID, List.of(new EvidenceId("evidence-obligation")),
                EvidenceSourceCoverage.PARTIAL, ObservationEvaluatorTest.eligible(),
                ComparisonOutcome.NOT_COMPARED, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of("METHOD_NOT_TRACKED"), NOW);
        return new ObservationEvaluator().evaluate(predicate, evidence);
    }

    private static InvestigationState state(
            ObservationPredicate predicate,
            org.example.algorithmdebug.contracts.investigation.ObservationEvaluation evaluation) {
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("problem-obligation"),
                CASE_ID, ANALYSIS_ID, "Wrong candidate", "Candidate A", "Candidate B",
                new TargetTest("fixture.AlgorithmTest", "selectsCandidate"),
                List.of(ANCHOR), List.of(), List.of("Selected branch"), NOW);
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, HYPOTHESIS_ID, CASE_ID, ANALYSIS_ID,
                "The wrong branch is selected", HypothesisStatus.OPEN, List.of(ANCHOR),
                List.of(), List.of(), List.of(GAP_ID), NOW);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, GAP_ID, CASE_ID, ANALYSIS_ID,
                "Which branch is selected?", EvidenceGapStatus.OPEN,
                List.of(HYPOTHESIS_ID), List.of(PREDICATE_ID), NOW);
        return new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE, CASE_ID, ANALYSIS_ID, frame,
                List.of(hypothesis), List.of(gap), List.of(predicate), List.of(evaluation),
                4, List.of());
    }

    private static AnalysisControlView control(
            List<String> satisfied, List<String> remaining) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW, CoordinationPolicyVersions.CURRENT,
                IDENTITY, 4, AnalysisActionType.ANALYSIS_STATUS, ActionDecisionCode.ALLOWED,
                List.of(), satisfied, remaining, List.of(), List.of(GAP_ID.value()),
                List.of(), List.of(), List.of(PREDICATE_ID.value()),
                List.of(AnalysisActionType.ANALYSIS_STATUS),
                ConclusionStatus.MISSING_EVIDENCE);
    }
}
