package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.coordination.ConclusionClaim;
import org.example.algorithmdebug.contracts.coordination.ConclusionDecision;
import org.example.algorithmdebug.contracts.coordination.ConclusionFinalization;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.investigation.CausalChain;
import org.example.algorithmdebug.contracts.investigation.CausalChainId;
import org.example.algorithmdebug.contracts.investigation.CausalEdge;
import org.example.algorithmdebug.contracts.investigation.CausalNode;
import org.example.algorithmdebug.contracts.investigation.CausalNodeType;
import org.junit.jupiter.api.Test;

class AnalysisFinalizeActionPolicyTest {

    @Test
    void acceptsTheExactSubmittedCandidate() {
        ConclusionCandidate candidate = candidate("Submitted candidate");
        var request = PolicyTestFixtures.request(
                AnalysisActionType.ANALYSIS_FINALIZE,
                new CoreActionInputs.AnalysisFinalize(candidate), true, Optional.empty());
        var policy = AnalysisActionPolicies.analysisFinalize(
                new PolicyTestFixtures.Prerequisites());

        var verified = policy.verify(
                PolicyTestFixtures.view(), request, finalization(candidate),
                PolicyTestFixtures.view());

        assertEquals(ActionDecisionCode.ALLOWED, verified.decision());
    }

    @Test
    void rejectsAReplacedCandidateEvenWhenItsIdentityAndRevisionMatch() {
        ConclusionCandidate submitted = candidate("Submitted candidate");
        ConclusionCandidate replaced = candidate("Different candidate content");
        var request = PolicyTestFixtures.request(
                AnalysisActionType.ANALYSIS_FINALIZE,
                new CoreActionInputs.AnalysisFinalize(submitted), true, Optional.empty());
        var policy = AnalysisActionPolicies.analysisFinalize(
                new PolicyTestFixtures.Prerequisites());

        var verified = policy.verify(
                PolicyTestFixtures.view(), request, finalization(replaced),
                PolicyTestFixtures.view());

        assertEquals(ActionDecisionCode.REJECTED, verified.decision());
        assertEquals(List.of(CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED),
                verified.reasonCodes());
    }

    private static ConclusionFinalization finalization(ConclusionCandidate candidate) {
        ConclusionDecision decision = new ConclusionDecision(
                SchemaVersions.CONCLUSION_DECISION,
                CoordinationPolicyVersions.CURRENT,
                candidate.conclusionId(), candidate.identity(), candidate.basedOnRevision(),
                ActionDecisionCode.ALLOWED, ConclusionStatus.CONFIRMED,
                List.of(), List.of(), List.of(AnalysisActionType.CASE_AUDIT));
        return new ConclusionFinalization(
                SchemaVersions.CONCLUSION_FINALIZATION, candidate, decision);
    }

    private static ConclusionCandidate candidate(String statement) {
        EvidenceId evidenceId = new EvidenceId("evidence-1");
        CausalChain chain = new CausalChain(
                SchemaVersions.CAUSAL_CHAIN, new CausalChainId("chain-1"),
                PolicyTestFixtures.IDENTITY.caseId(), PolicyTestFixtures.IDENTITY.analysisId(),
                List.of(
                        new CausalNode(
                                "cause", CausalNodeType.UPSTREAM_CAUSE, "Upstream state",
                                List.of(evidenceId), List.of()),
                        new CausalNode(
                                "symptom", CausalNodeType.SYMPTOM, "Observed symptom",
                                List.of(evidenceId), List.of())),
                List.of(new CausalEdge(
                        "cause-symptom", "cause", "symptom", "produces",
                        ClaimClassification.CONFIRMED_FACT, true,
                        List.of(evidenceId), List.of())),
                Instant.parse("2026-09-29T00:00:00Z"));
        ConclusionClaim claim = new ConclusionClaim(
                "claim-1", statement, ClaimClassification.CONFIRMED_FACT,
                List.of(evidenceId), List.of(), List.of("cause-symptom"));
        return new ConclusionCandidate(
                SchemaVersions.CONCLUSION_CANDIDATE, "conclusion-1",
                PolicyTestFixtures.IDENTITY, PolicyTestFixtures.view().revision(),
                ConclusionStatus.CONFIRMED, List.of(claim), List.of(chain), List.of());
    }
}
