package org.example.algorithmdebug.contracts.coordination;

import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 从不可变归档重建的有界控制视图，不是可覆盖的事实源。
 *
 * @param schemaVersion Schema 版本
 * @param policyVersion Policy 版本
 * @param identity 分析身份
 * @param revision 投影修订号
 * @param requestedAction 当前请求动作
 * @param decision 当前授权结果
 * @param reasonCodes 拒绝原因码
 * @param satisfiedObligationIds 已满足义务
 * @param remainingObligationIds 剩余义务
 * @param contradictionIds 矛盾 ID
 * @param openGapIds 开放证据缺口 ID
 * @param supportedHypothesisIds 已支持假设 ID
 * @param refutedHypothesisIds 已否定假设 ID
 * @param unevaluatedPredicateIds 未评估 Predicate ID
 * @param allowedActions 当前允许动作
 * @param terminalEligibility 当前允许的最高结论等级
 */
public record AnalysisControlView(
        String schemaVersion,
        String policyVersion,
        AnalysisIdentity identity,
        long revision,
        AnalysisActionType requestedAction,
        ActionDecisionCode decision,
        List<CoordinationErrorCode> reasonCodes,
        List<String> satisfiedObligationIds,
        List<String> remainingObligationIds,
        List<String> contradictionIds,
        List<String> openGapIds,
        List<String> supportedHypothesisIds,
        List<String> refutedHypothesisIds,
        List<String> unevaluatedPredicateIds,
        List<AnalysisActionType> allowedActions,
        ConclusionStatus terminalEligibility) {

    /** 校验视图版本、决策、集合唯一性和修订号。 */
    public AnalysisControlView {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.ANALYSIS_CONTROL_VIEW, "AnalysisControlView");
        policyVersion = CoordinationContractChecks.requireText(
                policyVersion, "policyVersion", CoordinationLimits.MAX_POLICY_VERSION_LENGTH, false);
        identity = CoordinationContractChecks.requireNonNull(identity, "identity");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        requestedAction = CoordinationContractChecks.requireNonNull(
                requestedAction, "requestedAction");
        decision = CoordinationContractChecks.requireNonNull(decision, "decision");
        reasonCodes = CoordinationContractChecks.immutableUniqueList(
                reasonCodes, "reasonCodes", CoordinationLimits.MAX_CONTROL_ITEMS);
        ActionDecision.requireDecisionReasons(decision, reasonCodes);
        satisfiedObligationIds = CoordinationContractChecks.immutableUniqueIds(
                satisfiedObligationIds, "satisfiedObligationIds");
        remainingObligationIds = CoordinationContractChecks.immutableUniqueIds(
                remainingObligationIds, "remainingObligationIds");
        contradictionIds = CoordinationContractChecks.immutableUniqueIds(
                contradictionIds, "contradictionIds");
        openGapIds = CoordinationContractChecks.immutableUniqueIds(openGapIds, "openGapIds");
        supportedHypothesisIds = CoordinationContractChecks.immutableUniqueIds(
                supportedHypothesisIds, "supportedHypothesisIds");
        refutedHypothesisIds = CoordinationContractChecks.immutableUniqueIds(
                refutedHypothesisIds, "refutedHypothesisIds");
        unevaluatedPredicateIds = CoordinationContractChecks.immutableUniqueIds(
                unevaluatedPredicateIds, "unevaluatedPredicateIds");
        allowedActions = CoordinationContractChecks.immutableUniqueList(
                allowedActions, "allowedActions", CoordinationLimits.MAX_CONTROL_ITEMS);
        terminalEligibility = CoordinationContractChecks.requireNonNull(
                terminalEligibility, "terminalEligibility");
        if (satisfiedObligationIds.stream().anyMatch(remainingObligationIds::contains)) {
            throw new IllegalArgumentException(
                    "An obligation cannot be both satisfied and remaining");
        }
        if (supportedHypothesisIds.stream().anyMatch(refutedHypothesisIds::contains)) {
            throw new IllegalArgumentException(
                    "A hypothesis cannot be both supported and refuted");
        }
    }
}
