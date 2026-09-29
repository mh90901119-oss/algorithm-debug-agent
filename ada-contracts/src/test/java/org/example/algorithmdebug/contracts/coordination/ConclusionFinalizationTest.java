package org.example.algorithmdebug.contracts.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.investigation.CausalChain;
import org.example.algorithmdebug.contracts.investigation.CausalChainId;
import org.example.algorithmdebug.contracts.investigation.CausalEdge;
import org.example.algorithmdebug.contracts.investigation.CausalNode;
import org.example.algorithmdebug.contracts.investigation.CausalNodeType;
import org.junit.jupiter.api.Test;

class ConclusionFinalizationTest {

    @Test
    void preservesTheExactCandidateAndGateDecision() {
        Fixture fixture = fixture();

        ConclusionFinalization result = new ConclusionFinalization(
                SchemaVersions.CONCLUSION_FINALIZATION,
                fixture.candidate(), fixture.decision());

        assertEquals(fixture.candidate(), result.candidate());
        assertEquals(fixture.decision(), result.decision());
    }

    @Test
    void rejectsAConclusionIdMismatch() {
        Fixture fixture = fixture();
        ConclusionDecision mismatch = decision(
                "another-conclusion", fixture.identity(), fixture.candidate().basedOnRevision());

        assertThrows(IllegalArgumentException.class, () -> new ConclusionFinalization(
                SchemaVersions.CONCLUSION_FINALIZATION, fixture.candidate(), mismatch));
    }

    @Test
    void rejectsAnAnalysisIdentityMismatch() {
        Fixture fixture = fixture();
        AnalysisIdentity otherIdentity = new AnalysisIdentity(
                fixture.identity().projectId(), fixture.identity().caseId(),
                new AnalysisId("analysis-2"));
        ConclusionDecision mismatch = decision(
                fixture.candidate().conclusionId(), otherIdentity,
                fixture.candidate().basedOnRevision());

        assertThrows(IllegalArgumentException.class, () -> new ConclusionFinalization(
                SchemaVersions.CONCLUSION_FINALIZATION, fixture.candidate(), mismatch));
    }

    @Test
    void rejectsAControlRevisionMismatch() {
        Fixture fixture = fixture();
        ConclusionDecision mismatch = decision(
                fixture.candidate().conclusionId(), fixture.identity(),
                fixture.candidate().basedOnRevision() + 1);

        assertThrows(IllegalArgumentException.class, () -> new ConclusionFinalization(
                SchemaVersions.CONCLUSION_FINALIZATION, fixture.candidate(), mismatch));
    }

    private static Fixture fixture() {
        AnalysisIdentity identity = new AnalysisIdentity(
                new ProjectId("project-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"));
        EvidenceId evidenceId = new EvidenceId("evidence-1");
        CausalChain chain = new CausalChain(
                SchemaVersions.CAUSAL_CHAIN, new CausalChainId("chain-1"),
                identity.caseId(), identity.analysisId(),
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
                "claim-1", "Evidence supports the causal edge.",
                ClaimClassification.CONFIRMED_FACT,
                List.of(evidenceId), List.of(), List.of("cause-symptom"));
        ConclusionCandidate candidate = new ConclusionCandidate(
                SchemaVersions.CONCLUSION_CANDIDATE, "conclusion-1", identity, 7,
                ConclusionStatus.CONFIRMED, List.of(claim), List.of(chain), List.of());
        return new Fixture(candidate, decision(candidate.conclusionId(), identity, 7), identity);
    }

    private static ConclusionDecision decision(
            String conclusionId, AnalysisIdentity identity, long revision) {
        return new ConclusionDecision(
                SchemaVersions.CONCLUSION_DECISION, CoordinationPolicyVersions.CURRENT,
                conclusionId, identity, revision,
                ActionDecisionCode.ALLOWED, ConclusionStatus.CONFIRMED,
                List.of(), List.of(), List.of(AnalysisActionType.CASE_AUDIT));
    }

    private record Fixture(
            ConclusionCandidate candidate,
            ConclusionDecision decision,
            AnalysisIdentity identity) {
    }
}
