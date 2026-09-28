package org.example.algorithmdebug.contracts.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;

class CoordinationContractsTest {

    @Test
    void actionTypesExactlyMatchSeventeenBaselineActions() {
        assertEquals(EnumSet.of(
                AnalysisActionType.ANALYSIS_BEGIN,
                AnalysisActionType.CASE_INSPECT,
                AnalysisActionType.ALGORITHM_INPUT_CAPTURE,
                AnalysisActionType.CASE_AUDIT,
                AnalysisActionType.GANTT_INSPECT,
                AnalysisActionType.RUN_TEST,
                AnalysisActionType.STATIC_ANALYZE,
                AnalysisActionType.SOURCE_QUERY,
                AnalysisActionType.INVESTIGATION_UPDATE,
                AnalysisActionType.CODEPATH_PLAN_CREATE,
                AnalysisActionType.CODEPATH_COLLECT,
                AnalysisActionType.JDWP_PLAN_CREATE,
                AnalysisActionType.JDWP_COLLECT,
                AnalysisActionType.ARTIFACT_READ,
                AnalysisActionType.EVIDENCE_QUERY,
                AnalysisActionType.ANALYSIS_STATUS,
                AnalysisActionType.ANALYSIS_FINALIZE),
                EnumSet.allOf(AnalysisActionType.class));
    }

    @Test
    void targetRejectsCrossIdentityAndBlankWorkspace() {
        AnalysisIdentity identity = identity();
        assertThrows(IllegalArgumentException.class, () -> new ActionTarget(
                " ", identity.projectId(), identity.caseId(), identity.analysisId(),
                Optional.empty(), Optional.empty(), Optional.empty()));

        ActionTarget crossAnalysisTarget = new ActionTarget(
                "workspace-1", identity.projectId(), identity.caseId(),
                new AnalysisId("analysis-other"), Optional.empty(), Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                AnalysisActionType.CASE_INSPECT,
                identity,
                crossAnalysisTarget,
                Optional.empty(),
                Map.of("depth", "summary")));
    }

    @Test
    void coordinatedResultRequiresControlForEveryOutcome() {
        for (ActionOutcome outcome : ActionOutcome.values()) {
            assertThrows(NullPointerException.class, () -> new CoordinatedToolResult<>(
                    SchemaVersions.COORDINATED_TOOL_RESULT,
                    outcome,
                    "ACTION_RESULT",
                    "completed",
                    Map.of(),
                    List.of(),
                    null));
        }
    }

    @Test
    void rejectedResultCannotCarrySideEffectArtifacts() {
        ArtifactReference artifact = new ArtifactReference(
                "artifact-1", "RUN_SUMMARY", "runs/run-1/summary.json",
                "application/json", "a".repeat(64), 12);
        assertThrows(IllegalArgumentException.class, () -> new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.REJECTED,
                CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED.name(),
                "action rejected",
                Map.of(),
                List.of(artifact),
                control(ActionDecisionCode.REJECTED)));
    }

    @Test
    void conclusionClaimRequiresTypedClassificationAndReferences() {
        ConclusionClaim claim = new ConclusionClaim(
                "claim-1",
                "The selected branch creates the observed ordering.",
                ClaimClassification.CONFIRMED_FACT,
                List.of(new EvidenceId("evidence-1")),
                List.of(),
                List.of());
        assertEquals(ClaimClassification.CONFIRMED_FACT, claim.classification());
        assertEquals(List.of(new EvidenceId("evidence-1")), claim.evidenceIds());

        assertThrows(NullPointerException.class, () -> new ConclusionClaim(
                "claim-2", "Unsupported claim", null,
                List.of(new EvidenceId("evidence-1")), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConclusionClaim(
                "claim-3", "Unreferenced claim", ClaimClassification.LLM_HYPOTHESIS,
                List.of(), List.of(), List.of()));
    }

    private static AnalysisIdentity identity() {
        return new AnalysisIdentity(
                new ProjectId("project-1"),
                new CaseId("case-1"),
                new AnalysisId("analysis-1"));
    }

    private static AnalysisControlView control(ActionDecisionCode decision) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                identity(),
                3,
                AnalysisActionType.CASE_INSPECT,
                decision,
                decision == ActionDecisionCode.ALLOWED
                        ? List.of()
                        : List.of(CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED),
                List.of("obligation-1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(AnalysisActionType.CASE_INSPECT),
                ConclusionStatus.MISSING_EVIDENCE);
    }
}
