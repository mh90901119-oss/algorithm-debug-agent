package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.coordination.ActionDecision;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;

/** Investigation Update 的 typed 身份和命令门禁；模型不能提交状态或 Evaluation。 */
public final class InvestigationUpdatePolicy implements
        AnalysisActionPolicy<CoreActionInputs.InvestigationUpdate, InvestigationState> {
    private final AnalysisActionPolicy<CoreActionInputs.InvestigationUpdate, InvestigationState>
            delegate;

    /** 构造只依赖已验证 prerequisite 的纯 Policy。 */
    public InvestigationUpdatePolicy(CoreActionPrerequisites prerequisites) {
        if (prerequisites == null) {
            throw new IllegalArgumentException("prerequisites must not be null");
        }
        this.delegate = ActionPolicySupport.policy(
                AnalysisActionType.INVESTIGATION_UPDATE,
                ActionSideEffect.CASE_WRITE,
                (before, request) -> {
                    CoordinationErrorCode initialized = ActionPolicySupport.requireInitialized(
                            prerequisites, request);
                    if (initialized != null) {
                        return initialized;
                    }
                    var command = request.payload().command();
                    return command.caseId().equals(request.identity().caseId())
                            && command.analysisId().equals(request.identity().analysisId())
                            ? null : CoordinationErrorCode.COORDINATION_IDENTITY_MISMATCH;
                },
                (before, request, result, after) -> result != null
                        && result.caseId().equals(request.identity().caseId())
                        && result.analysisId().equals(request.identity().analysisId())
                        ? null : CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED);
    }

    @Override
    public ActionDecision authorize(
            AnalysisControlView before,
            AnalysisActionRequest<CoreActionInputs.InvestigationUpdate> request) {
        return delegate.authorize(before, request);
    }

    @Override
    public ActionDecision verify(
            AnalysisControlView before,
            AnalysisActionRequest<CoreActionInputs.InvestigationUpdate> request,
            InvestigationState result,
            AnalysisControlView after) {
        return delegate.verify(before, request, result, after);
    }
}
