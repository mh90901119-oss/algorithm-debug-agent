package org.example.algorithmdebug.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.InvestigationEventArchive;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.example.algorithmdebug.plan.InvestigationBindingRequest;

/** Core 用例测试共享的最小、完整、确定性 Investigation Ledger。 */
final class InvestigationTestFixture {
    private static final HypothesisId HYPOTHESIS_ID =
            new HypothesisId("hypothesis-1");
    private static final EvidenceGapId GAP_ID = new EvidenceGapId("gap-1");
    private static final ObservationPredicateId PREDICATE_ID =
            new ObservationPredicateId("predicate-1");

    private InvestigationTestFixture() {
    }

    static InvestigationBindingRequest request(String question) {
        return request(question, List.of());
    }

    static InvestigationBindingRequest request(
            String question, List<EvidenceId> basedOnEvidenceIds) {
        return new InvestigationBindingRequest(
                question, GAP_ID, List.of(HYPOTHESIS_ID), List.of(PREDICATE_ID),
                basedOnEvidenceIds);
    }

    static void archive(
            Path workspace,
            ProjectId projectId,
            CaseId caseId,
            AnalysisId analysisId,
            TargetTest targetTest,
            SourceAnchor anchor,
            Instant now) {
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"), caseId,
                analysisId, "unexpected algorithm result", "expected result", "actual result",
                targetTest, List.of(anchor), List.of("run:run-1"),
                List.of("which runtime path produced the result"), now);
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, HYPOTHESIS_ID, caseId, analysisId,
                "the selected runtime path produced the result", HypothesisStatus.OPEN,
                List.of(anchor), List.of(), List.of(), List.of(GAP_ID), now);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, GAP_ID, caseId, analysisId,
                "which runtime path produced the result", EvidenceGapStatus.OPEN,
                List.of(HYPOTHESIS_ID), List.of(PREDICATE_ID), now);
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, PREDICATE_ID, caseId, analysisId,
                HYPOTHESIS_ID, GAP_ID, ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches(), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, now);
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        InvestigationEventArchive events = new InvestigationEventArchive(
                WorkspaceLayout.of(workspace).projectCases(projectId), mapper,
                new AtomicDocumentWriter());
        AnalysisIdentity identity = new AnalysisIdentity(projectId, caseId, analysisId);
        events.appendEvent(identity, new InvestigationEvent.ProblemFrameDefined(
                SchemaVersions.INVESTIGATION_EVENT, "event-frame", caseId, analysisId,
                1, now, frame));
        events.appendEvent(identity, new InvestigationEvent.HypothesisAdded(
                SchemaVersions.INVESTIGATION_EVENT, "event-hypothesis", caseId, analysisId,
                2, now.plusSeconds(1), hypothesis));
        events.appendEvent(identity, new InvestigationEvent.EvidenceGapAdded(
                SchemaVersions.INVESTIGATION_EVENT, "event-gap", caseId, analysisId,
                3, now.plusSeconds(2), gap));
        events.appendEvent(identity, new InvestigationEvent.PredicateRegistered(
                SchemaVersions.INVESTIGATION_EVENT, "event-predicate", caseId, analysisId,
                4, now.plusSeconds(3), predicate));
    }
}
