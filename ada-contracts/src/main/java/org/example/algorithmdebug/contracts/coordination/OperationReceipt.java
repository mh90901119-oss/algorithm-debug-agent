package org.example.algorithmdebug.contracts.coordination;

import java.util.Optional;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 幂等操作完成或失败后的稳定回执。
 *
 * @param schemaVersion Schema 版本
 * @param operationId 操作 ID
 * @param identity 分析身份
 * @param actionType 动作类型
 * @param outcome 终态结果
 * @param inputSha256 规范化输入哈希
 * @param replayed 是否由历史终态回放
 * @param resultArtifactId 可选结果 Artifact ID
 * @param errorCode 失败或拒绝的稳定错误码
 */
public record OperationReceipt(
        String schemaVersion,
        OperationId operationId,
        AnalysisIdentity identity,
        AnalysisActionType actionType,
        ActionOutcome outcome,
        String inputSha256,
        boolean replayed,
        Optional<String> resultArtifactId,
        Optional<CoordinationErrorCode> errorCode) {

    /** 校验操作身份、输入哈希和结果/错误的一致性。 */
    public OperationReceipt {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.OPERATION_RECEIPT, "OperationReceipt");
        operationId = CoordinationContractChecks.requireNonNull(operationId, "operationId");
        identity = CoordinationContractChecks.requireNonNull(identity, "identity");
        actionType = CoordinationContractChecks.requireNonNull(actionType, "actionType");
        outcome = CoordinationContractChecks.requireNonNull(outcome, "outcome");
        inputSha256 = CoordinationContractChecks.requireSha256(inputSha256, "inputSha256");
        resultArtifactId = CoordinationContractChecks.optional(
                resultArtifactId, "resultArtifactId").map(
                        value -> CoordinationContractChecks.requireId(value, "resultArtifactId"));
        errorCode = CoordinationContractChecks.optional(errorCode, "errorCode");
        if (outcome == ActionOutcome.SUCCEEDED && errorCode.isPresent()) {
            throw new IllegalArgumentException("A successful operation cannot contain errorCode");
        }
        if (outcome != ActionOutcome.SUCCEEDED && errorCode.isEmpty()) {
            throw new IllegalArgumentException("A non-successful operation requires errorCode");
        }
    }
}
