package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.ActionTarget;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.junit.jupiter.api.Test;

class AnalysisActionRegistryTest {
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));

    @Test
    void everyRegisteredActionHasExactlyOnePolicyAndHandler() {
        AnalysisActionBinding<String, String> binding = binding(AnalysisActionType.CASE_INSPECT);

        AnalysisActionRegistry registry = AnalysisActionRegistry.of(List.of(binding));

        assertSame(binding, registry.require(AnalysisActionType.CASE_INSPECT));
        assertEquals(List.of(binding), registry.bindings());
        assertThrows(UnsupportedOperationException.class, () -> registry.bindings().clear());
    }

    @Test
    void duplicateActionRegistrationIsRejected() {
        AnalysisActionBinding<String, String> first = binding(AnalysisActionType.CASE_INSPECT);
        AnalysisActionBinding<String, String> second = binding(AnalysisActionType.CASE_INSPECT);

        assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisActionRegistry.of(List.of(first, second)));
    }

    @Test
    void bindingRejectsPayloadOfAnotherTypeBeforeCallingHandler() {
        AnalysisActionBinding<String, String> binding = binding(AnalysisActionType.CASE_INSPECT);

        assertThrows(IllegalArgumentException.class, () -> binding.requirePayload(42));
    }

    private static AnalysisActionBinding<String, String> binding(AnalysisActionType actionType) {
        AnalysisActionPolicy<String, String> policy = new AnalysisActionPolicy<>() {
            @Override
            public ActionDecision authorize(
                    AnalysisControlView before, AnalysisActionRequest<String> request) {
                return allowed(before, request.actionType(), ActionSideEffect.READ_ONLY);
            }

            @Override
            public ActionDecision verify(
                    AnalysisControlView before,
                    AnalysisActionRequest<String> request,
                    String result,
                    AnalysisControlView after) {
                return allowed(after, request.actionType(), ActionSideEffect.READ_ONLY);
            }
        };
        return new AnalysisActionBinding<>(
                actionType,
                String.class,
                ActionSideEffect.READ_ONLY,
                policy,
                (target, input, cancellation) -> input,
                result -> AnalysisActionBinding.ResultMetadata.withoutArtifacts(
                        "CASE_INSPECT_COMPLETED", "Case inspection completed"));
    }

    private static ActionDecision allowed(
            AnalysisControlView view,
            AnalysisActionType actionType,
            ActionSideEffect sideEffect) {
        return new ActionDecision(
                SchemaVersions.ACTION_DECISION,
                CoordinationPolicyVersions.CURRENT,
                IDENTITY,
                view.revision(),
                actionType,
                ActionDecisionCode.ALLOWED,
                sideEffect,
                List.of());
    }

    @SuppressWarnings("unused")
    private static AnalysisActionRequest<String> request(AnalysisActionType actionType) {
        return new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                actionType,
                IDENTITY,
                new ActionTarget(
                        "workspace-1", IDENTITY.projectId(), IDENTITY.caseId(),
                        IDENTITY.analysisId(), Optional.empty(), Optional.empty(), Optional.empty()),
                Optional.empty(),
                "input");
    }
}
