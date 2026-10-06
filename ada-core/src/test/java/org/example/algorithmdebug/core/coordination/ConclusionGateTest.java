package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ConclusionDecisionArchive;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
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
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.coordination.ConclusionClaim;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
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
import org.example.algorithmdebug.evidence.CausalChainEligibilityEvaluator;
import org.example.algorithmdebug.evidence.EvidenceEligibilityEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConclusionGateTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-gate"), new CaseId("case-gate"),
            new AnalysisId("analysis-gate"));
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-gate");
    private static final SourceQueryId QUERY_ID = new SourceQueryId("query-gate");
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

    @TempDir Path temporaryDirectory;
    private ConclusionDecisionArchive archive;
    private ConclusionGate gate;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        archive = new ConclusionDecisionArchive(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
        var eligibility = new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, false, ComparisonOutcome.NOT_COMPARED, true));
        var catalog = new CausalChainEligibilityEvaluator.ReferenceCatalog(
                IDENTITY, Map.of(EVIDENCE_ID, eligibility), Set.of(QUERY_ID),
                Set.of("artifact-gate"));
        gate = new ConclusionGate(
                catalog, archive, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    }

    @Test
    void acceptsConfirmedCandidateOnlyAfterCausalAndCounterevidenceChecks() {
        var decision = gate.evaluate(candidate(
                ConclusionStatus.CONFIRMED, validClaim()), control(ConclusionStatus.CONFIRMED),
                state());

        assertEquals(ActionDecisionCode.ALLOWED, decision.decision());
        assertEquals(ConclusionStatus.CONFIRMED, decision.allowedStatus());
        assertTrue(decision.reasonCodes().isEmpty());
        assertTrue(decision.missingEvidence().isEmpty());
        assertEquals(candidate(ConclusionStatus.CONFIRMED, validClaim()),
                archive.requireCandidate(IDENTITY, "conclusion-gate").candidate());
        assertEquals(decision,
                archive.requireDecision(IDENTITY, "conclusion-gate").decision());
    }

    @Test
    void naturalLanguageClaimMustReferenceAcceptedNodeOrEdge() {
        ConclusionClaim ungrounded = new ConclusionClaim(
                "claim-ungrounded", "A visually nearby method is the root cause.",
                ClaimClassification.LLM_HYPOTHESIS, List.of(), List.of(),
                List.of("unaccepted-node"));

        var decision = gate.evaluate(
                candidate(ConclusionStatus.CONFIRMED, ungrounded),
                control(ConclusionStatus.CONFIRMED), state());

        assertEquals(ActionDecisionCode.REJECTED, decision.decision());
        assertEquals(ConclusionStatus.MISSING_EVIDENCE, decision.allowedStatus());
        assertEquals(List.of("unaccepted-node"), decision.missingEvidence());
        assertEquals(List.of(
                AnalysisActionType.SOURCE_QUERY, AnalysisActionType.JDWP_PLAN_CREATE),
                decision.allowedActions());
    }

    @Test
    void rejectedCandidateIsArchivedWithAllowedLevelMissingEvidenceAndAllowedActions() {
        AnalysisControlView constrained = new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW, CoordinationPolicyVersions.CURRENT,
                IDENTITY, 4, AnalysisActionType.ANALYSIS_FINALIZE, ActionDecisionCode.ALLOWED,
                List.of(), List.of(), List.of("predicate-runtime"),
                List.of("contradiction-runtime"), List.of(TARGET_GAP.value()),
                List.of(TARGET_ID.value()), List.of(ALTERNATIVE_ID.value()),
                List.of("predicate-runtime"),
                List.of(AnalysisActionType.SOURCE_QUERY, AnalysisActionType.JDWP_PLAN_CREATE),
                ConclusionStatus.BOUNDED_HYPOTHESIS);

        var decision = gate.evaluate(
                candidate(ConclusionStatus.CONFIRMED, validClaim()), constrained, state());

        assertEquals(ActionDecisionCode.REJECTED, decision.decision());
        assertEquals(ConclusionStatus.BOUNDED_HYPOTHESIS, decision.allowedStatus());
        assertTrue(decision.missingEvidence().contains("predicate-runtime"));
        assertTrue(decision.missingEvidence().contains(TARGET_GAP.value()));
        assertEquals(constrained.allowedActions(), decision.allowedActions());
        assertEquals(decision,
                archive.requireDecision(IDENTITY, "conclusion-gate").decision());
    }

    @Test
    void insufficientOverallStatusMayStillContainDirectConfirmedFacts() {
        ConclusionClaim directFact = new ConclusionClaim(
                "claim-direct", "The target UT produced the observed runtime state.",
                ClaimClassification.CONFIRMED_FACT, List.of(EVIDENCE_ID), List.of(),
                List.of("runtime"));

        var decision = gate.evaluate(
                candidate(ConclusionStatus.MISSING_EVIDENCE, directFact),
                control(ConclusionStatus.MISSING_EVIDENCE), state());

        assertEquals(ActionDecisionCode.ALLOWED, decision.decision());
        assertEquals(ConclusionStatus.MISSING_EVIDENCE, decision.allowedStatus());
    }

    private static ConclusionClaim validClaim() {
        return new ConclusionClaim(
                "claim-gate", "The source branch creates the runtime state and symptom.",
                ClaimClassification.VALIDATOR_CONCLUSION, List.of(EVIDENCE_ID), List.of(),
                List.of("source-runtime", "runtime-symptom"));
    }

    private static ConclusionCandidate candidate(
            ConclusionStatus status, ConclusionClaim claim) {
        return new ConclusionCandidate(
                SchemaVersions.CONCLUSION_CANDIDATE, "conclusion-gate", IDENTITY, 4,
                status, List.of(claim), List.of(chain()), List.of());
    }

    private static CausalChain chain() {
        return new CausalChain(
                SchemaVersions.CAUSAL_CHAIN, new CausalChainId("chain-gate"),
                IDENTITY.caseId(), IDENTITY.analysisId(),
                List.of(
                        node("source", CausalNodeType.SOURCE_MECHANISM),
                        node("runtime", CausalNodeType.RUNTIME_STATE),
                        node("symptom", CausalNodeType.SYMPTOM)),
                List.of(
                        edge("source-runtime", "source", "runtime"),
                        edge("runtime-symptom", "runtime", "symptom")), NOW);
    }

    private static CausalNode node(String id, CausalNodeType type) {
        return new CausalNode(
                id, type, id, List.of(EVIDENCE_ID), List.of(QUERY_ID));
    }

    private static CausalEdge edge(String id, String from, String to) {
        return new CausalEdge(
                id, from, to, "causes", ClaimClassification.VALIDATOR_CONCLUSION,
                true, List.of(EVIDENCE_ID), List.of(QUERY_ID));
    }

    private static AnalysisControlView control(ConclusionStatus status) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW, CoordinationPolicyVersions.CURRENT,
                IDENTITY, 4, AnalysisActionType.ANALYSIS_FINALIZE, ActionDecisionCode.ALLOWED,
                List.of(), List.of("runtime-observed"), List.of(), List.of(), List.of(),
                List.of(TARGET_ID.value()), List.of(ALTERNATIVE_ID.value()), List.of(),
                List.of(AnalysisActionType.SOURCE_QUERY, AnalysisActionType.JDWP_PLAN_CREATE),
                status);
    }

    private static InvestigationState state() {
        ObservationPredicate targetPredicate = predicate(
                TARGET_PREDICATE, TARGET_ID, TARGET_GAP);
        ObservationPredicate alternativePredicate = predicate(
                ALTERNATIVE_PREDICATE, ALTERNATIVE_ID, ALTERNATIVE_GAP);
        ObservationEvaluation targetEvaluation = evaluation(
                TARGET_EVALUATION, TARGET_PREDICATE, ObservationTruth.TRUE,
                HypothesisEffect.SUPPORT);
        ObservationEvaluation alternativeEvaluation = evaluation(
                ALTERNATIVE_EVALUATION, ALTERNATIVE_PREDICATE, ObservationTruth.FALSE,
                HypothesisEffect.REFUTE);
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("problem-gate"),
                IDENTITY.caseId(), IDENTITY.analysisId(), "Wrong schedule",
                "Expected order", "Actual order",
                new TargetTest("fixture.AlgorithmTest", "fails"), List.of(ANCHOR),
                List.of("run-gate"), List.of("root cause"), NOW);
        return new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE, IDENTITY.caseId(), IDENTITY.analysisId(), frame,
                List.of(
                        hypothesis(TARGET_ID, TARGET_GAP, HypothesisStatus.SUPPORTED,
                                List.of(TARGET_EVALUATION), List.of()),
                        hypothesis(ALTERNATIVE_ID, ALTERNATIVE_GAP, HypothesisStatus.REFUTED,
                                List.of(), List.of(ALTERNATIVE_EVALUATION))),
                List.of(
                        gap(TARGET_GAP, TARGET_ID, TARGET_PREDICATE),
                        gap(ALTERNATIVE_GAP, ALTERNATIVE_ID, ALTERNATIVE_PREDICATE)),
                List.of(targetPredicate, alternativePredicate),
                List.of(targetEvaluation, alternativeEvaluation), 12, List.of());
    }

    private static HypothesisRecord hypothesis(
            HypothesisId id,
            EvidenceGapId gapId,
            HypothesisStatus status,
            List<ObservationEvaluationId> support,
            List<ObservationEvaluationId> contradiction) {
        return new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, id, IDENTITY.caseId(), IDENTITY.analysisId(),
                "Hypothesis " + id.value(), status, List.of(ANCHOR), support, contradiction,
                List.of(gapId), NOW);
    }

    private static EvidenceGap gap(
            EvidenceGapId gapId,
            HypothesisId hypothesisId,
            ObservationPredicateId predicateId) {
        return new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, gapId, IDENTITY.caseId(), IDENTITY.analysisId(),
                "Which branch explains the symptom?", EvidenceGapStatus.CLOSED,
                List.of(hypothesisId), List.of(predicateId), NOW);
    }

    private static ObservationPredicate predicate(
            ObservationPredicateId predicateId,
            HypothesisId hypothesisId,
            EvidenceGapId gapId) {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, predicateId,
                IDENTITY.caseId(), IDENTITY.analysisId(), hypothesisId, gapId,
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Algorithm#solve()V"),
                PredicateRole.CRITICAL, HypothesisEffect.SUPPORT,
                HypothesisEffect.REFUTE, HypothesisEffect.NO_CHANGE, NOW);
    }

    private static ObservationEvaluation evaluation(
            ObservationEvaluationId id,
            ObservationPredicateId predicateId,
            ObservationTruth truth,
            HypothesisEffect effect) {
        return new ObservationEvaluation(
                SchemaVersions.OBSERVATION_EVALUATION, id,
                IDENTITY.caseId(), IDENTITY.analysisId(), predicateId, truth,
                List.of(EVIDENCE_ID), EvidenceSourceCoverage.COMPLETE,
                EvidenceDisposition.CONFIRMATION_ELIGIBLE, true, effect,
                List.of(), "a".repeat(64), NOW, "test-evaluator");
    }
}
