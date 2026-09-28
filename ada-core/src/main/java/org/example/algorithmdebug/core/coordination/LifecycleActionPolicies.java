package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;

/** Analysis 生命周期动作的机械授权规则。 */
public final class LifecycleActionPolicies {
    private LifecycleActionPolicies() {
    }

    /** 仅允许在 manifest 与 journal 均不存在的引导态创建 Analysis。 */
    public static AnalysisActionPolicy<CoreActionInputs.AnalysisBegin, CaseOpenResult>
            analysisBegin(CoreActionPrerequisites prerequisites) {
        requirePrerequisites(prerequisites);
        return ActionPolicySupport.policy(
                AnalysisActionType.ANALYSIS_BEGIN,
                ActionSideEffect.CASE_WRITE,
                (before, request) -> {
                    var input = request.payload();
                    if (prerequisites.analysisInitialization(request.identity())
                            != CoreActionPrerequisites.AnalysisInitialization.ABSENT) {
                        return CoordinationErrorCode.COORDINATION_STATE_INVALID;
                    }
                    if (!input.problemFrame().caseId().equals(request.identity().caseId())
                            || !input.problemFrame().analysisId().equals(
                            request.identity().analysisId())) {
                        return CoordinationErrorCode.COORDINATION_IDENTITY_MISMATCH;
                    }
                    return null;
                },
                (before, request, result, after) -> {
                    if (result == null
                            || !result.caseId().equals(request.identity().caseId())
                            || !result.analysisId().equals(request.identity().analysisId())
                            || prerequisites.analysisInitialization(request.identity())
                            != CoreActionPrerequisites.AnalysisInitialization.INITIALIZED) {
                        return CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED;
                    }
                    return null;
                });
    }

    private static void requirePrerequisites(CoreActionPrerequisites prerequisites) {
        if (prerequisites == null) {
            throw new IllegalArgumentException("prerequisites must not be null");
        }
    }
}
