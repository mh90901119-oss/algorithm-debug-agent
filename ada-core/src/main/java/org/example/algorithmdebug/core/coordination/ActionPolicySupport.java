package org.example.algorithmdebug.core.coordination;

import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;

/** 各 Action Policy 共用的纯决策构造与模板，不执行 I/O。 */
final class ActionPolicySupport {
    private ActionPolicySupport() {
    }

    static <I, O> AnalysisActionPolicy<I, O> policy(
            AnalysisActionType actionType,
            ActionSideEffect sideEffect,
            AuthorizationRule<I> authorization,
            VerificationRule<I, O> verification) {
        return new AnalysisActionPolicy<>() {
            @Override
            public ActionDecision authorize(
                    AnalysisControlView before, AnalysisActionRequest<I> request) {
                CoordinationErrorCode rejection = commonRejection(
                        actionType, before, request);
                if (rejection == null) {
                    rejection = authorization.rejection(before, request);
                }
                return decision(before, request, sideEffect, rejection);
            }

            @Override
            public ActionDecision verify(
                    AnalysisControlView before,
                    AnalysisActionRequest<I> request,
                    O result,
                    AnalysisControlView after) {
                CoordinationErrorCode rejection = commonRejection(
                        actionType, after, request);
                if (rejection == null) {
                    rejection = verification.rejection(before, request, result, after);
                }
                return decision(after, request, sideEffect, rejection);
            }
        };
    }

    static CoordinationErrorCode requireInitialized(
            CoreActionPrerequisites prerequisites,
            AnalysisActionRequest<?> request) {
        return prerequisites.analysisInitialization(request.identity())
                == CoreActionPrerequisites.AnalysisInitialization.INITIALIZED
                ? null : CoordinationErrorCode.COORDINATION_PREREQUISITE_MISSING;
    }

    private static CoordinationErrorCode commonRejection(
            AnalysisActionType expected,
            AnalysisControlView view,
            AnalysisActionRequest<?> request) {
        if (view == null || request == null
                || request.actionType() != expected
                || !request.identity().equals(view.identity())
                || !request.target().belongsTo(request.identity())) {
            return CoordinationErrorCode.COORDINATION_IDENTITY_MISMATCH;
        }
        return null;
    }

    private static ActionDecision decision(
            AnalysisControlView view,
            AnalysisActionRequest<?> request,
            ActionSideEffect sideEffect,
            CoordinationErrorCode rejection) {
        return new ActionDecision(
                SchemaVersions.ACTION_DECISION,
                view.policyVersion(),
                request.identity(),
                view.revision(),
                request.actionType(),
                rejection == null ? ActionDecisionCode.ALLOWED : ActionDecisionCode.REJECTED,
                sideEffect,
                rejection == null ? List.of() : List.of(rejection));
    }

    @FunctionalInterface
    interface AuthorizationRule<I> {
        CoordinationErrorCode rejection(
                AnalysisControlView before, AnalysisActionRequest<I> request);
    }

    @FunctionalInterface
    interface VerificationRule<I, O> {
        CoordinationErrorCode rejection(
                AnalysisControlView before,
                AnalysisActionRequest<I> request,
                O result,
                AnalysisControlView after);
    }
}
