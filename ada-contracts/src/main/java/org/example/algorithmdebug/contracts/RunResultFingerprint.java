package org.example.algorithmdebug.contracts;

import java.util.Optional;

/**
 * 一次目标 UT 运行的确定性结果指纹。
 *
 * <p>Gantt 原始文件与 JSON 内容指纹成对出现；目标失败指纹可单独出现，也可与断言失败时仍产出的
 * Gantt 同时出现。本契约只描述可观察结果是否一致，不表达算法业务原因。</p>
 *
 * @param schemaVersion 契约版本
 * @param caseId 所属 Case
 * @param contextId 本次运行使用的 Context
 * @param runId 产生该指纹的 Run
 * @param targetFailureSha256 可选的目标失败 SHA-256
 */
public record RunResultFingerprint(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        RunId runId,
        String targetFailureSha256) {

    /** 校验版本、身份和观察维度，并把所有 SHA-256 规范为小写。 */
    public RunResultFingerprint {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.RUN_RESULT_FINGERPRINT.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "不支持的 RunResultFingerprint schemaVersion: " + schemaVersion);
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        targetFailureSha256 = ContractChecks.requireSha256(targetFailureSha256, "targetFailureSha256");
    }

}
