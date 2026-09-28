package org.example.algorithmdebug.contracts.coordination;

import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 动作执行前后的可归档确定性 Policy 决策。
 *
 * @param schemaVersion Schema 版本
 * @param policyVersion Policy 版本
 * @param identity 分析身份
 * @param revision 决策依据的状态修订号
 * @param requestedAction 被判定的动作
 * @param decision 授权结果
 * @param sideEffect 动作副作用等级
 * @param reasonCodes 拒绝原因码
 */
public record ActionDecision(
        String schemaVersion,
        String policyVersion,
        AnalysisIdentity identity,
        long revision,
        AnalysisActionType requestedAction,
        ActionDecisionCode decision,
        ActionSideEffect sideEffect,
        List<CoordinationErrorCode> reasonCodes) {

    /** 校验版本、修订号和允许/拒绝原因的一致性。 */
    public ActionDecision {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.ACTION_DECISION, "ActionDecision");
        policyVersion = CoordinationContractChecks.requireText(
                policyVersion, "policyVersion", CoordinationLimits.MAX_POLICY_VERSION_LENGTH, false);
        identity = CoordinationContractChecks.requireNonNull(identity, "identity");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        requestedAction = CoordinationContractChecks.requireNonNull(
                requestedAction, "requestedAction");
        decision = CoordinationContractChecks.requireNonNull(decision, "decision");
        sideEffect = CoordinationContractChecks.requireNonNull(sideEffect, "sideEffect");
        reasonCodes = CoordinationContractChecks.immutableUniqueList(
                reasonCodes, "reasonCodes", CoordinationLimits.MAX_CONTROL_ITEMS);
        requireDecisionReasons(decision, reasonCodes);
    }

    static void requireDecisionReasons(
            ActionDecisionCode decision, List<CoordinationErrorCode> reasonCodes) {
        if (decision == ActionDecisionCode.ALLOWED && !reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("An allowed decision cannot contain rejection reasons");
        }
        if (decision == ActionDecisionCode.REJECTED && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("A rejected decision requires at least one reason");
        }
    }
}
