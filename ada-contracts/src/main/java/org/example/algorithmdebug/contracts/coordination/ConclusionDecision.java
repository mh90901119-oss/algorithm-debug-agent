package org.example.algorithmdebug.contracts.coordination;

import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * ConclusionGate 对候选的确定性决策。
 *
 * @param schemaVersion Schema 版本
 * @param policyVersion Policy 版本
 * @param conclusionId 结论 ID
 * @param identity 分析身份
 * @param evaluatedRevision 评估修订号
 * @param decision 接受或拒绝
 * @param allowedStatus 当前证据允许的最高等级
 * @param reasonCodes 拒绝原因码
 * @param missingEvidence 缺失 Evidence、Gap 或 Predicate ID
 */
public record ConclusionDecision(
        String schemaVersion,
        String policyVersion,
        String conclusionId,
        AnalysisIdentity identity,
        long evaluatedRevision,
        ActionDecisionCode decision,
        ConclusionStatus allowedStatus,
        List<CoordinationErrorCode> reasonCodes,
        List<String> missingEvidence) {

    /** 校验版本、身份、修订号和接受/拒绝原因的一致性。 */
    public ConclusionDecision {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.CONCLUSION_DECISION, "ConclusionDecision");
        policyVersion = CoordinationContractChecks.requireText(
                policyVersion, "policyVersion", CoordinationLimits.MAX_POLICY_VERSION_LENGTH, false);
        conclusionId = CoordinationContractChecks.requireId(conclusionId, "conclusionId");
        identity = CoordinationContractChecks.requireNonNull(identity, "identity");
        if (evaluatedRevision < 0) {
            throw new IllegalArgumentException("evaluatedRevision must not be negative");
        }
        decision = CoordinationContractChecks.requireNonNull(decision, "decision");
        allowedStatus = CoordinationContractChecks.requireNonNull(allowedStatus, "allowedStatus");
        reasonCodes = CoordinationContractChecks.immutableUniqueList(
                reasonCodes, "reasonCodes", CoordinationLimits.MAX_CONTROL_ITEMS);
        missingEvidence = CoordinationContractChecks.immutableUniqueIds(
                missingEvidence, "missingEvidence");
        ActionDecision.requireDecisionReasons(decision, reasonCodes);
        if (decision == ActionDecisionCode.ALLOWED && !missingEvidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "An accepted conclusion cannot contain missingEvidence");
        }
    }
}
