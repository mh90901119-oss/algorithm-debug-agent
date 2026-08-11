package org.example.algorithmdebug.contracts;

import java.time.Instant;

/**
 * 一次无动态采集基线运行的不可变清单。
 *
 * @param schemaVersion 清单 Schema 版本
 * @param projectId 被分析项目 ID
 * @param caseId 问题 Case ID
 * @param runId 本次运行 ID
 * @param capturedAt 清单生成的 UTC/带时区墙钟时间
 * @param targetTest 目标 JUnit 测试
 * @param executionIdentity 执行身份与语义结果哈希
 * @param scheduleResult 调度结果产物引用
 */
public record BaselineManifest(
        String schemaVersion,
        ProjectId projectId,
        CaseId caseId,
        RunId runId,
        Instant capturedAt,
        TargetTest targetTest,
        ExecutionIdentity executionIdentity,
        ArtifactReference scheduleResult) {

    /** 校验基线清单必填字段和当前 Schema 版本。 */
    public BaselineManifest {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.BASELINE_MANIFEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 BaselineManifest schemaVersion: " + schemaVersion);
        }
        projectId = ContractChecks.requireNonNull(projectId, "projectId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        capturedAt = ContractChecks.requireNonNull(capturedAt, "capturedAt");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        executionIdentity = ContractChecks.requireNonNull(executionIdentity, "executionIdentity");
        scheduleResult = ContractChecks.requireNonNull(scheduleResult, "scheduleResult");
        if (!targetTest.selector().equals(executionIdentity.caseFingerprint().testSelector())) {
            throw new IllegalArgumentException("targetTest 与 executionIdentity.testSelector 不一致");
        }
    }
}
