package org.example.algorithmdebug.contracts;

import java.time.Instant;

/**
 * Maven 进程启动前必须持久化的一次无采集目标 UT 运行请求。
 *
 * @param schemaVersion Schema 版本
 * @param caseId 所属 Case
 * @param analysisId 发起运行的 Analysis
 * @param runId Run ID
 * @param targetTest 目标 UT
 * @param executionMode 当前固定为 `UNINSTRUMENTED`
 * @param createdAt 创建时间
 */
public record RunRequest(
        String schemaVersion,
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        TargetTest targetTest,
        String executionMode,
        Instant createdAt) {

    /** 校验运行归属、目标测试和当前支持的执行模式。 */
    public RunRequest {
        schemaVersion = CaseManifest.requireVersion(
                schemaVersion, SchemaVersions.RUN_REQUEST, "RunRequest");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        executionMode = ContractChecks.requireNonBlank(executionMode, "executionMode");
        if (!"UNINSTRUMENTED".equals(executionMode)) {
            throw new IllegalArgumentException("Only UNINSTRUMENTED execution mode is supported");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
