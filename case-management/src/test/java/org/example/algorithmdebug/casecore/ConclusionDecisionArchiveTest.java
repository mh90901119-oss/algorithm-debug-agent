package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.coordination.ConclusionClaim;
import org.example.algorithmdebug.contracts.coordination.ConclusionDecision;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.investigation.CausalChain;
import org.example.algorithmdebug.contracts.investigation.CausalChainId;
import org.example.algorithmdebug.contracts.investigation.CausalEdge;
import org.example.algorithmdebug.contracts.investigation.CausalNode;
import org.example.algorithmdebug.contracts.investigation.CausalNodeType;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConclusionDecisionArchiveTest {
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @TempDir Path temporaryDirectory;
    private ConclusionDecisionArchive archive;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        archive = new ConclusionDecisionArchive(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    @Test
    void appendsCandidateBeforeRejectedDecisionWithHashesAndProvenance() {
        ConclusionCandidate candidate = candidate("Observed runtime state");
        Path candidatePath = archive.appendCandidate(
                candidate, CoordinationPolicyVersions.CURRENT,
                List.of("analysis:analysis-1", "revision:4"), NOW);
        Path decisionPath = archive.appendDecision(
                rejected(), List.of("candidate:conclusion-1"), NOW.plusSeconds(1));

        var archivedCandidate = archive.requireCandidate(IDENTITY, "conclusion-1");
        var archivedDecision = archive.requireDecision(IDENTITY, "conclusion-1");

        assertEquals("candidate.json", candidatePath.getFileName().toString());
        assertEquals("rejected.json", decisionPath.getFileName().toString());
        assertEquals(candidate, archivedCandidate.candidate());
        assertEquals(rejected(), archivedDecision.decision());
        assertEquals(64, archivedCandidate.inputSha256().length());
        assertEquals(64, archivedCandidate.contentSha256().length());
        assertEquals(archivedCandidate.inputSha256(), archivedDecision.candidateSha256());
    }

    @Test
    void exactCandidateReplayIsIdempotentButConflictingContentCannotOverwrite() {
        ConclusionCandidate candidate = candidate("Observed runtime state");
        Path first = archive.appendCandidate(
                candidate, CoordinationPolicyVersions.CURRENT,
                List.of("analysis:analysis-1"), NOW);
        Path replay = archive.appendCandidate(
                candidate, CoordinationPolicyVersions.CURRENT,
                List.of("analysis:analysis-1"), NOW);

        assertEquals(first, replay);
        assertThrows(WorkspaceException.class, () -> archive.appendCandidate(
                candidate("Different conclusion"), CoordinationPolicyVersions.CURRENT,
                List.of("analysis:analysis-1"), NOW));
    }

    @Test
    void terminalRequiresCandidateAndAcceptedRejectedAreMutuallyExclusive() {
        assertThrows(WorkspaceException.class, () -> archive.appendDecision(
                rejected(), List.of("candidate:conclusion-1"), NOW));

        archive.appendCandidate(
                candidate("Observed runtime state"), CoordinationPolicyVersions.CURRENT,
                List.of("analysis:analysis-1"), NOW);
        ConclusionDecision accepted = new ConclusionDecision(
                SchemaVersions.CONCLUSION_DECISION, CoordinationPolicyVersions.CURRENT,
                "conclusion-1", IDENTITY, 4, ActionDecisionCode.ALLOWED,
                ConclusionStatus.CONFIRMED, List.of(), List.of(), List.of());
        archive.appendDecision(
                accepted, List.of("candidate:conclusion-1"), NOW.plusSeconds(1));

        assertThrows(WorkspaceException.class, () -> archive.appendDecision(
                rejected(), List.of("candidate:conclusion-1"), NOW.plusSeconds(2)));
    }

    private static ConclusionDecision rejected() {
        return new ConclusionDecision(
                SchemaVersions.CONCLUSION_DECISION, CoordinationPolicyVersions.CURRENT,
                "conclusion-1", IDENTITY, 4, ActionDecisionCode.REJECTED,
                ConclusionStatus.BOUNDED_HYPOTHESIS,
                List.of(CoordinationErrorCode.CONCLUSION_NOT_ELIGIBLE),
                List.of("gap-1"), List.of(AnalysisActionType.JDWP_PLAN_CREATE));
    }

    private static ConclusionCandidate candidate(String statement) {
        EvidenceId evidenceId = new EvidenceId("evidence-1");
        SourceQueryId queryId = new SourceQueryId("query-1");
        CausalChain chain = new CausalChain(
                SchemaVersions.CAUSAL_CHAIN, new CausalChainId("chain-1"),
                IDENTITY.caseId(), IDENTITY.analysisId(),
                List.of(
                        new CausalNode(
                                "source", CausalNodeType.SOURCE_MECHANISM, "Source",
                                List.of(), List.of(queryId)),
                        new CausalNode(
                                "runtime", CausalNodeType.RUNTIME_STATE, "Runtime",
                                List.of(evidenceId), List.of()),
                        new CausalNode(
                                "symptom", CausalNodeType.SYMPTOM, "Symptom",
                                List.of(evidenceId), List.of())),
                List.of(
                        new CausalEdge(
                                "source-runtime", "source", "runtime", "causes",
                                ClaimClassification.VALIDATOR_CONCLUSION, true,
                                List.of(evidenceId), List.of(queryId)),
                        new CausalEdge(
                                "runtime-symptom", "runtime", "symptom", "causes",
                                ClaimClassification.CONFIRMED_FACT, true,
                                List.of(evidenceId), List.of())), NOW);
        ConclusionClaim claim = new ConclusionClaim(
                "claim-1", statement, ClaimClassification.VALIDATOR_CONCLUSION,
                List.of(evidenceId), List.of(), List.of("runtime"));
        return new ConclusionCandidate(
                SchemaVersions.CONCLUSION_CANDIDATE, "conclusion-1", IDENTITY, 4,
                ConclusionStatus.CONFIRMED, List.of(claim), List.of(chain), List.of());
    }
}
