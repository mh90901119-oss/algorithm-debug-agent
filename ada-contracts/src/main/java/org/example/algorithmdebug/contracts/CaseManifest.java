package org.example.algorithmdebug.contracts;

import java.time.Instant;

/**
 * 一个用户问题对应的不可变 Case 身份。
 *
 * @param schemaVersion Schema 版本
 * @param caseId Case ID
 * @param projectId 已登记算法项目 ID
 * @param targetTest Case 冻结的目标 UT
 * @param initialQuestion 创建 Case 的问题
 * @param createdAt 创建时间
 */
public record CaseManifest(
        String schemaVersion,
        CaseId caseId,
        ProjectId projectId,
        TargetTest targetTest,
        String initialQuestion,
        Instant createdAt) {

    /** 校验 Case 身份和问题边界。 */
    public CaseManifest {
        schemaVersion = requireVersion(schemaVersion, SchemaVersions.CASE_MANIFEST, "CaseManifest");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        projectId = ContractChecks.requireNonNull(projectId, "projectId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        initialQuestion = ContractChecks.requireBoundedText(
                initialQuestion, "initialQuestion", 65_536, false);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    static String requireVersion(String actual, String expected, String type) {
        String checked = ContractChecks.requireNonBlank(actual, "schemaVersion");
        if (!expected.equals(checked)) {
            throw new IllegalArgumentException("不支持的 " + type + " schemaVersion: " + checked);
        }
        return checked;
    }
}
