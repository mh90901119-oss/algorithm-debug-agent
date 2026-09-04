package org.example.algorithmdebug.contracts;

/**
 * 一次目标 UT 失败的确定性结果指纹。
 *
 * <p>该指纹只用于比较结构化目标失败，不包含 Gantt 内容，也不判断源码版本。</p>
 *
 * @param schemaVersion 契约版本
 * @param caseId 所属 Case
 * @param analysisId 产生该指纹的 Analysis
 * @param runId 产生该指纹的普通 Run
 * @param targetFailureSha256 目标失败 SHA-256
 */
public record RunResultFingerprint(
        String schemaVersion,
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        String targetFailureSha256) {

    /** 校验版本、Analysis 归属和失败指纹。 */
    public RunResultFingerprint {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.RUN_RESULT_FINGERPRINT.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported RunResultFingerprint schemaVersion: " + schemaVersion);
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        targetFailureSha256 = ContractChecks.requireSha256(
                targetFailureSha256, "targetFailureSha256");
    }
}