package org.example.algorithmdebug.core.coordination;

import org.example.algorithmdebug.contracts.coordination.ActionSideEffect;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;

/** 已初始化 Analysis 的只读动作策略。 */
public final class ReadActionPolicies {
    private ReadActionPolicies() {
    }

    /** 构造不改变状态、但要求 Analysis 完整初始化的只读 Policy。 */
    public static <I, O> AnalysisActionPolicy<I, O> initialized(
            AnalysisActionType actionType,
            CoreActionPrerequisites prerequisites) {
        if (actionType == null || prerequisites == null) {
            throw new IllegalArgumentException("actionType and prerequisites must not be null");
        }
        return ActionPolicySupport.policy(
                actionType,
                ActionSideEffect.READ_ONLY,
                (before, request) -> ActionPolicySupport.requireInitialized(
                        prerequisites, request),
                (before, request, result, after) -> result == null
                        ? CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED : null);
    }
}
