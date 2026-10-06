package org.example.algorithmdebug.core.coordination;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionTarget;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.coordination.OperationId;

final class PolicyTestFixtures {
    static final AnalysisIdentity IDENTITY = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));

    private PolicyTestFixtures() {
    }

    static AnalysisControlView view() {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                IDENTITY,
                4,
                AnalysisActionType.ANALYSIS_STATUS,
                ActionDecisionCode.ALLOWED,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(),
                EnumSet.allOf(AnalysisActionType.class).stream().toList(),
                ConclusionStatus.BOUNDED_HYPOTHESIS);
    }

    static <T> AnalysisActionRequest<T> request(
            AnalysisActionType actionType,
            T payload,
            boolean operationId,
            Optional<PlanId> planId) {
        return new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                actionType,
                IDENTITY,
                new ActionTarget(
                        "workspace-1", IDENTITY.projectId(), IDENTITY.caseId(),
                        IDENTITY.analysisId(), Optional.empty(), planId, Optional.empty()),
                operationId ? Optional.of(new OperationId("operation-1")) : Optional.empty(),
                payload);
    }

    static final class Prerequisites implements CoreActionPrerequisites {
        AnalysisInitialization initialization = AnalysisInitialization.INITIALIZED;
        boolean inputCaptured = true;
        boolean methodCatalog = true;
        boolean actionAvailable = true;
        final Set<String> codePathPlans = new HashSet<>();
        final Set<String> jdwpPlans = new HashSet<>();

        @Override
        public AnalysisInitialization analysisInitialization(AnalysisIdentity identity) {
            return initialization;
        }

        @Override
        public boolean algorithmInputCaptured(AnalysisIdentity identity) {
            return inputCaptured;
        }

        @Override
        public boolean methodCatalogAvailable(AnalysisIdentity identity) {
            return methodCatalog;
        }

        @Override
        public boolean planAvailable(
                AnalysisIdentity identity, PlanId planId, PlanKind planKind) {
            return (planKind == PlanKind.CODEPATH ? codePathPlans : jdwpPlans)
                    .contains(planId.value());
        }

        @Override
        public boolean actionAvailable(AnalysisActionType actionType) {
            return actionAvailable;
        }
    }
}
