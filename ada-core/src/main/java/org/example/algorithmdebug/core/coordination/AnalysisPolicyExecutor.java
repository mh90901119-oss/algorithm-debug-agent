package org.example.algorithmdebug.core.coordination;

import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;

/** 集中执行并校验 typed Policy，防止错误 Policy 绕过公共身份和 operationId 规则。 */
final class AnalysisPolicyExecutor {

    <I, O> ActionDecision authorize(
            AnalysisActionBinding<I, O> binding,
            AnalysisControlView before,
            AnalysisActionRequest<I> request) {
        final ActionDecision policyDecision;
        try {
            policyDecision = binding.policy().authorize(before, request);
        } catch (RuntimeException policyFailure) {
            throw failure("Action authorization policy failed", policyFailure);
        }
        ActionDecision checked = validate(binding, request, before, policyDecision);
        if (checked.decision() == ActionDecisionCode.REJECTED) {
            return checked;
        }
        if (before.decision() == ActionDecisionCode.REJECTED) {
            CoordinationErrorCode reason = before.reasonCodes().isEmpty()
                    ? CoordinationErrorCode.COORDINATION_STATE_INVALID
                    : before.reasonCodes().getFirst();
            return rejected(binding, request, before, reason);
        }
        if (binding.sideEffect() != ActionSideEffect.READ_ONLY
                && request.operationId().isEmpty()) {
            return rejected(
                    binding, request, before,
                    CoordinationErrorCode.ACTION_OPERATION_ID_REQUIRED);
        }
        return checked;
    }

    <I, O> ActionDecision verify(
            AnalysisActionBinding<I, O> binding,
            AnalysisControlView before,
            AnalysisActionRequest<I> request,
            O output,
            AnalysisControlView after) {
        final ActionDecision policyDecision;
        try {
            policyDecision = binding.policy().verify(before, request, output, after);
        } catch (RuntimeException policyFailure) {
            throw failure("Action postcondition policy failed", policyFailure);
        }
        return validate(binding, request, after, policyDecision);
    }

    ActionDecision rejected(
            AnalysisActionBinding<?, ?> binding,
            AnalysisActionRequest<?> request,
            AnalysisControlView view,
            CoordinationErrorCode reason) {
        return new ActionDecision(
                SchemaVersions.ACTION_DECISION,
                view.policyVersion(),
                request.identity(),
                view.revision(),
                request.actionType(),
                ActionDecisionCode.REJECTED,
                binding.sideEffect(),
                List.of(reason));
    }

    private ActionDecision validate(
            AnalysisActionBinding<?, ?> binding,
            AnalysisActionRequest<?> request,
            AnalysisControlView view,
            ActionDecision decision) {
        if (decision == null
                || !decision.identity().equals(request.identity())
                || decision.requestedAction() != request.actionType()
                || decision.sideEffect() != binding.sideEffect()
                || decision.revision() != view.revision()) {
            throw failure(
                    "Policy decision does not match request, binding and projected revision",
                    null);
        }
        return decision;
    }

    private static AnalysisCoordinator.ExecutionFailure failure(
            String message, Throwable cause) {
        return new AnalysisCoordinator.ExecutionFailure(
                CoordinationErrorCode.COORDINATION_STATE_INVALID, message, cause);
    }
}
