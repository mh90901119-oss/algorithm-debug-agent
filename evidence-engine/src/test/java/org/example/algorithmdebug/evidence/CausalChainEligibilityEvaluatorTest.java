package org.example.algorithmdebug.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.CausalChain;
import org.example.algorithmdebug.contracts.investigation.CausalChainId;
import org.example.algorithmdebug.contracts.investigation.CausalEdge;
import org.example.algorithmdebug.contracts.investigation.CausalNode;
import org.example.algorithmdebug.contracts.investigation.CausalNodeType;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluationId;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;
import org.junit.jupiter.api.Test;

class CausalChainEligibilityEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-chain");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-chain");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-chain");
    private static final SourceQueryId QUERY_ID = new SourceQueryId("query-chain");
    private static final HypothesisId TARGET_ID = new HypothesisId("hypothesis-target");
    private static final HypothesisId ALTERNATIVE_ID = new HypothesisId("hypothesis-alternative");
    private static final EvidenceGapId TARGET_GAP = new EvidenceGapId("gap-target");
    private static final EvidenceGapId ALTERNATIVE_GAP = new EvidenceGapId("gap-alternative");
    private static final ObservationPredicateId TARGET_PREDICATE =
            new ObservationPredicateId("predicate-target");
    private static final ObservationPredicateId ALTERNATIVE_PREDICATE =
            new ObservationPredicateId("predicate-alternative");
    private static final ObservationEvaluationId TARGET_EVALUATION =
            new ObservationEvaluationId("evaluation-target");
    private static final ObservationEvaluationId ALTERNATIVE_EVALUATION =
            new ObservationEvaluationId("evaluation-alternative");
    private static final SourceAnchor ANCHOR = new SourceAnchor(
            "fixture.Algorithm", "solve", "()V",
            "src/main/java/fixture/Algorithm.java", 1, 20);

    private final CausalChainEligibilityEvaluator evaluator =
            new CausalChainEligibilityEvaluator();

    @Test
    void confirmedRequiresSymptomRuntimeOrDecisionAndSourceMechanismPath() {
        var confirmed = evaluator.evaluate(confirmedChain(), confirmedState(), catalog(eligible()));
        var incomplete = evaluator.evaluate(
                sourceToSymptomChain(), confirmedState(), catalog(eligible()));

        assertEquals(ConclusionStatus.CONFIRMED, confirmed.allowedStatus());
        assertTrue(confirmed.reasonCodes().isEmpty());
        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, incomplete.allowedStatus());
        assertTrue(incomplete.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.CAUSAL_PATH_INCOMPLETE));
    }

    @Test
    void sourceOnlyChainIsAtMostBoundedHypothesis() {
        var result = evaluator.evaluate(sourceOnlyChain(), confirmedState(), catalog(eligible()));

        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, result.allowedStatus());
        assertTrue(result.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.DYNAMIC_CRITICAL_EDGE_MISSING));
    }

    @Test
    void confirmedRequiresTwoGroundedHypothesesAndOneRefutedAlternative() {
        InvestigationState onlyTarget = state(
                List.of(target(HypothesisStatus.SUPPORTED)),
                List.of(gap(TARGET_GAP, TARGET_ID, TARGET_PREDICATE, EvidenceGapStatus.CLOSED)),
                List.of(predicate(TARGET_PREDICATE, TARGET_ID, TARGET_GAP)),
                List.of(evaluation(TARGET_EVALUATION, TARGET_PREDICATE,
                        ObservationTruth.TRUE, EvidenceSourceCoverage.COMPLETE,
                        EvidenceDisposition.CONFIRMATION_ELIGIBLE, HypothesisEffect.SUPPORT)));

        var result = evaluator.evaluate(confirmedChain(), onlyTarget, catalog(eligible()));

        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, result.allowedStatus());
        assertTrue(result.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.ALTERNATIVE_NOT_REFUTED));
    }

    @Test
    void openEquivalentAlternativeBlocksConfirmed() {
        HypothesisId openId = new HypothesisId("hypothesis-open");
        EvidenceGapId openGap = new EvidenceGapId("gap-open");
        InvestigationState state = state(
                List.of(target(HypothesisStatus.SUPPORTED), alternative(),
                        hypothesis(openId, openGap, HypothesisStatus.OPEN, List.of(), List.of())),
                List.of(
                        gap(TARGET_GAP, TARGET_ID, TARGET_PREDICATE, EvidenceGapStatus.CLOSED),
                        gap(ALTERNATIVE_GAP, ALTERNATIVE_ID, ALTERNATIVE_PREDICATE,
                                EvidenceGapStatus.CLOSED),
                        new EvidenceGap(
                                SchemaVersions.EVIDENCE_GAP, openGap, CASE_ID, ANALYSIS_ID,
                                "Could another branch explain the symptom?", EvidenceGapStatus.OPEN,
                                List.of(openId), List.of(), NOW)),
                predicates(), evaluations());

        var result = evaluator.evaluate(confirmedChain(), state, catalog(eligible()));

        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, result.allowedStatus());
        assertTrue(result.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.OPEN_EQUIVALENT_ALTERNATIVE));
    }

    @Test
    void falseCriticalOrUnresolvedGapBlocksConfirmedTarget() {
        ObservationEvaluation falseTarget = evaluation(
                TARGET_EVALUATION, TARGET_PREDICATE, ObservationTruth.FALSE,
                EvidenceSourceCoverage.COMPLETE, EvidenceDisposition.CONFIRMATION_ELIGIBLE,
                HypothesisEffect.REFUTE);
        InvestigationState falseState = state(
                List.of(target(HypothesisStatus.SUPPORTED), alternative()),
                gaps(EvidenceGapStatus.CLOSED), predicates(),
                List.of(falseTarget, evaluations().get(1)));
        InvestigationState unresolvedState = state(
                List.of(target(HypothesisStatus.SUPPORTED), alternative()),
                gaps(EvidenceGapStatus.UNRESOLVED), predicates(), evaluations());

        var falseResult = evaluator.evaluate(confirmedChain(), falseState, catalog(eligible()));
        var unresolvedResult = evaluator.evaluate(
                confirmedChain(), unresolvedState, catalog(eligible()));

        assertTrue(falseResult.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.CRITICAL_PREDICATE_FALSE));
        assertTrue(unresolvedResult.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.TARGET_GAP_UNRESOLVED));
        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, falseResult.allowedStatus());
        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, unresolvedResult.allowedStatus());
    }

    @Test
    void changedFailureFingerprintBlocksConfirmedButAllowsBoundedClue() {
        var result = evaluator.evaluate(
                confirmedChain(), confirmedState(), catalog(changedFailure()));

        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, result.allowedStatus());
        assertTrue(result.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.DYNAMIC_CRITICAL_EDGE_INELIGIBLE));
    }

    @Test
    void incompleteCoverageAndUnknownCriticalBlockConfirmed() {
        ObservationEvaluation partial = evaluation(
                TARGET_EVALUATION, TARGET_PREDICATE, ObservationTruth.TRUE,
                EvidenceSourceCoverage.PARTIAL, EvidenceDisposition.CONFIRMATION_ELIGIBLE,
                HypothesisEffect.SUPPORT);
        ObservationEvaluation unknown = evaluation(
                TARGET_EVALUATION, TARGET_PREDICATE, ObservationTruth.UNKNOWN,
                EvidenceSourceCoverage.PARTIAL, EvidenceDisposition.CLUE_ONLY,
                HypothesisEffect.NO_CHANGE);
        InvestigationState partialState = state(
                List.of(target(HypothesisStatus.SUPPORTED), alternative()),
                gaps(EvidenceGapStatus.CLOSED), predicates(),
                List.of(partial, evaluations().get(1)));
        InvestigationState unknownState = state(
                List.of(target(HypothesisStatus.INCONCLUSIVE), alternative()),
                gaps(EvidenceGapStatus.CLOSED), predicates(),
                List.of(unknown, evaluations().get(1)));

        var partialResult = evaluator.evaluate(
                confirmedChain(), partialState, catalog(eligible()));
        var unknownResult = evaluator.evaluate(
                confirmedChain(), unknownState, catalog(eligible()));

        assertTrue(partialResult.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.CRITICAL_COVERAGE_INCOMPLETE));
        assertTrue(unknownResult.reasonCodes().contains(
                CausalChainEligibilityEvaluator.ReasonCode.CRITICAL_PREDICATE_UNKNOWN));
        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, partialResult.allowedStatus());
        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, unknownResult.allowedStatus());
    }

    private static CausalChain confirmedChain() {
        return chain(true, true);
    }

    private static CausalChain sourceOnlyChain() {
        return chain(false, true);
    }

    private static CausalChain sourceToSymptomChain() {
        CausalNode source = node("source", CausalNodeType.SOURCE_MECHANISM, false);
        CausalNode symptom = node("symptom", CausalNodeType.SYMPTOM, true);
        return new CausalChain(
                SchemaVersions.CAUSAL_CHAIN, new CausalChainId("chain-incomplete"),
                CASE_ID, ANALYSIS_ID, List.of(source, symptom),
                List.of(edge("source-symptom", "source", "symptom", true, true)), NOW);
    }

    private static CausalChain chain(boolean dynamicEdges, boolean includeRuntime) {
        CausalNode source = node("source", CausalNodeType.SOURCE_MECHANISM, dynamicEdges);
        CausalNode runtime = node("runtime", CausalNodeType.RUNTIME_STATE, dynamicEdges);
        CausalNode symptom = node("symptom", CausalNodeType.SYMPTOM, dynamicEdges);
        List<CausalNode> nodes = includeRuntime
                ? List.of(source, runtime, symptom) : List.of(source, symptom);
        List<CausalEdge> edges = includeRuntime
                ? List.of(
                        edge("source-runtime", "source", "runtime", dynamicEdges, true),
                        edge("runtime-symptom", "runtime", "symptom", dynamicEdges, true))
                : List.of(edge("source-symptom", "source", "symptom", dynamicEdges, true));
        return new CausalChain(
                SchemaVersions.CAUSAL_CHAIN,
                new CausalChainId(dynamicEdges ? "chain-confirmed" : "chain-source-only"),
                CASE_ID, ANALYSIS_ID, nodes, edges, NOW);
    }

    private static CausalNode node(String id, CausalNodeType type, boolean dynamic) {
        return new CausalNode(
                id, type, id,
                dynamic ? List.of(EVIDENCE_ID) : List.of(),
                List.of(QUERY_ID));
    }

    private static CausalEdge edge(
            String id, String from, String to, boolean dynamic, boolean critical) {
        return new CausalEdge(
                id, from, to, "causes", ClaimClassification.VALIDATOR_CONCLUSION,
                critical, dynamic ? List.of(EVIDENCE_ID) : List.of(), List.of(QUERY_ID));
    }

    private static InvestigationState confirmedState() {
        return state(
                List.of(target(HypothesisStatus.SUPPORTED), alternative()),
                gaps(EvidenceGapStatus.CLOSED), predicates(), evaluations());
    }

    private static List<EvidenceGap> gaps(EvidenceGapStatus targetStatus) {
        return List.of(
                gap(TARGET_GAP, TARGET_ID, TARGET_PREDICATE, targetStatus),
                gap(ALTERNATIVE_GAP, ALTERNATIVE_ID, ALTERNATIVE_PREDICATE,
                        EvidenceGapStatus.CLOSED));
    }

    private static List<ObservationPredicate> predicates() {
        return List.of(
                predicate(TARGET_PREDICATE, TARGET_ID, TARGET_GAP),
                predicate(ALTERNATIVE_PREDICATE, ALTERNATIVE_ID, ALTERNATIVE_GAP));
    }

    private static List<ObservationEvaluation> evaluations() {
        return List.of(
                evaluation(TARGET_EVALUATION, TARGET_PREDICATE, ObservationTruth.TRUE,
                        EvidenceSourceCoverage.COMPLETE,
                        EvidenceDisposition.CONFIRMATION_ELIGIBLE, HypothesisEffect.SUPPORT),
                evaluation(ALTERNATIVE_EVALUATION, ALTERNATIVE_PREDICATE,
                        ObservationTruth.FALSE, EvidenceSourceCoverage.COMPLETE,
                        EvidenceDisposition.CONFIRMATION_ELIGIBLE, HypothesisEffect.REFUTE));
    }

    private static HypothesisRecord target(HypothesisStatus status) {
        List<ObservationEvaluationId> support = status == HypothesisStatus.SUPPORTED
                ? List.of(TARGET_EVALUATION) : List.of();
        return hypothesis(TARGET_ID, TARGET_GAP, status, support, List.of());
    }

    private static HypothesisRecord alternative() {
        return hypothesis(
                ALTERNATIVE_ID, ALTERNATIVE_GAP, HypothesisStatus.REFUTED,
                List.of(), List.of(ALTERNATIVE_EVALUATION));
    }

    private static HypothesisRecord hypothesis(
            HypothesisId id,
            EvidenceGapId gapId,
            HypothesisStatus status,
            List<ObservationEvaluationId> support,
            List<ObservationEvaluationId> contradiction) {
        return new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, id, CASE_ID, ANALYSIS_ID,
                "Hypothesis " + id.value(), status, List.of(ANCHOR), support,
                contradiction, List.of(gapId), NOW);
    }

    private static EvidenceGap gap(
            EvidenceGapId gapId,
            HypothesisId hypothesisId,
            ObservationPredicateId predicateId,
            EvidenceGapStatus status) {
        return new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, gapId, CASE_ID, ANALYSIS_ID,
                "Which hypothesis explains the symptom?", status,
                List.of(hypothesisId), List.of(predicateId), NOW);
    }

    private static ObservationPredicate predicate(
            ObservationPredicateId predicateId,
            HypothesisId hypothesisId,
            EvidenceGapId gapId) {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, predicateId, CASE_ID, ANALYSIS_ID,
                hypothesisId, gapId, ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Algorithm#solve()V"),
                PredicateRole.CRITICAL, HypothesisEffect.SUPPORT,
                HypothesisEffect.REFUTE, HypothesisEffect.NO_CHANGE, NOW);
    }

    private static ObservationEvaluation evaluation(
            ObservationEvaluationId id,
            ObservationPredicateId predicateId,
            ObservationTruth truth,
            EvidenceSourceCoverage coverage,
            EvidenceDisposition disposition,
            HypothesisEffect effect) {
        boolean applied = effect != HypothesisEffect.NO_CHANGE;
        return new ObservationEvaluation(
                SchemaVersions.OBSERVATION_EVALUATION, id, CASE_ID, ANALYSIS_ID,
                predicateId, truth, List.of(EVIDENCE_ID), coverage, disposition,
                applied, effect, List.of(), "a".repeat(64), NOW, "test-evaluator");
    }

    private static InvestigationState state(
            List<HypothesisRecord> hypotheses,
            List<EvidenceGap> gaps,
            List<ObservationPredicate> predicates,
            List<ObservationEvaluation> evaluations) {
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("problem-chain"),
                CASE_ID, ANALYSIS_ID, "Wrong schedule", "Expected order", "Actual order",
                new TargetTest("fixture.AlgorithmTest", "fails"), List.of(ANCHOR),
                List.of("run-1"), List.of("root cause"), NOW);
        return new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE, CASE_ID, ANALYSIS_ID, frame,
                hypotheses, gaps, predicates, evaluations, 12, List.of());
    }

    private static CausalChainEligibilityEvaluator.ReferenceCatalog catalog(
            EvidenceEligibility eligibility) {
        return new CausalChainEligibilityEvaluator.ReferenceCatalog(
                new AnalysisIdentity(new ProjectId("project-chain"), CASE_ID, ANALYSIS_ID),
                Map.of(EVIDENCE_ID, eligibility), Set.of(QUERY_ID), Set.of("artifact-1"));
    }

    private static EvidenceEligibility eligible() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, false, ComparisonOutcome.NOT_COMPARED, true));
    }

    private static EvidenceEligibility changedFailure() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, true, ComparisonOutcome.CHANGED, true));
    }
}
