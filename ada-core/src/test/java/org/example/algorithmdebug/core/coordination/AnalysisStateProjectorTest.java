package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.casecore.AnalysisArtifactIndex;
import org.example.algorithmdebug.casecore.InvestigationJournalReader;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceEligibilityReason;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.junit.jupiter.api.Test;

class AnalysisStateProjectorTest {
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"),
            InvestigationStateProjectorTest.CASE_ID,
            InvestigationStateProjectorTest.ANALYSIS_ID);

    @Test
    void sameArtifactsInDifferentEnumerationOrderProduceSameControlView() {
        List<AnalysisArtifactIndex.Entry> entries = new ArrayList<>(List.of(
                entry("artifact-b"), entry("artifact-a")));
        AnalysisControlView first = projector(AnalysisArtifactIndex.from(entries)).project(IDENTITY);
        Collections.reverse(entries);
        AnalysisControlView second = projector(AnalysisArtifactIndex.from(entries)).project(IDENTITY);

        assertEquals(first, second);
        assertEquals(List.of(), first.satisfiedObligationIds());
        assertEquals(8, first.revision());
    }

    @Test
    void journalConflictMakesControlViewFailClosed() {
        AnalysisStateProjector projector = new AnalysisStateProjector(
                AnalysisArtifactIndex.from(List.of(entry("artifact-a"))),
                identity -> {
                    throw new WorkspaceException(
                            "INVESTIGATION_JOURNAL_CONFLICT", "conflicting sequence");
                },
                new InvestigationStateProjector());

        AnalysisControlView view = projector.project(IDENTITY);

        assertEquals(ActionDecisionCode.REJECTED, view.decision());
        assertEquals(List.of(CoordinationErrorCode.COORDINATION_STATE_INVALID), view.reasonCodes());
    }

    @Test
    void journalGapCapsTerminalEligibilityAtMissingEvidence() {
        AnalysisStateProjector projector = new AnalysisStateProjector(
                AnalysisArtifactIndex.from(List.of(entry("artifact-a"))),
                identity -> new InvestigationJournalReader.Result(
                        InvestigationStateProjectorTest.supportedOrRefutedEvents(
                                org.example.algorithmdebug.contracts.investigation.HypothesisEffect.SUPPORT),
                        List.of("INVESTIGATION_SEQUENCE_GAP:4")),
                new InvestigationStateProjector());

        assertEquals(ConclusionStatus.MISSING_EVIDENCE, projector.project(IDENTITY).terminalEligibility());
    }

    private static AnalysisStateProjector projector(AnalysisArtifactIndex index) {
        return new AnalysisStateProjector(
                index,
                identity -> new InvestigationJournalReader.Result(
                        InvestigationStateProjectorTest.supportedOrRefutedEvents(
                                org.example.algorithmdebug.contracts.investigation.HypothesisEffect.SUPPORT),
                        List.of()),
                new InvestigationStateProjector());
    }

    private static AnalysisArtifactIndex.Entry entry(String artifactId) {
        ArtifactReference artifact = new ArtifactReference(
                artifactId,
                "EVIDENCE_BUNDLE",
                "evidence/" + artifactId + ".json",
                "application/json",
                "b".repeat(64),
                64);
        EvidenceEligibility eligibility = new EvidenceEligibility(
                SchemaVersions.EVIDENCE_ELIGIBILITY,
                true, true, false, false, false, true, true,
                List.of(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name()));
        return new AnalysisArtifactIndex.Entry(
                IDENTITY,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new EvidenceId("evidence-" + artifactId)),
                artifact,
                Optional.of(eligibility));
    }
}
