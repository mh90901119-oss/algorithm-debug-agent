package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;

/**
 * 一次分析开始时可观察的目标源码、输入和构建声明快照。
 *
 * <p>该契约用于识别分析上下文变化，不声称包含 Maven 最终 classpath。</p>
 *
 * @param schemaVersion Schema 版本
 * @param caseId 所属 Case
 * @param contextId Context ID
 * @param projectId 已登记算法项目
 * @param targetTest 目标 UT
 * @param repositoryRevision 可获取的 Git revision 或 `UNAVAILABLE`
 * @param sourceSnapshot 有界源码摘要
 * @param inputSnapshot 算法输入状态和摘要
 * @param buildSnapshot POM、Java 和 Adapter 身份
 * @param completeness 整体快照完整性
 * @param fingerprintSha256 不含 ID、绝对路径和时间的规范化内容 Hash
 * @param warnings 最多 20 条有界缺口说明
 * @param createdAt 创建时间
 */
public record ContextSnapshot(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        ProjectId projectId,
        TargetTest targetTest,
        String repositoryRevision,
        SourceSnapshot sourceSnapshot,
        InputSnapshot inputSnapshot,
        BuildSnapshot buildSnapshot,
        SnapshotCompleteness completeness,
        String fingerprintSha256,
        List<String> warnings,
        Instant createdAt) {

    /** 校验 Context 归属、快照完整性和规范化 Fingerprint。 */
    public ContextSnapshot {
        schemaVersion = CaseManifest.requireVersion(
                schemaVersion, SchemaVersions.CONTEXT_SNAPSHOT, "ContextSnapshot");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        projectId = ContractChecks.requireNonNull(projectId, "projectId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        repositoryRevision = ContractChecks.requireBoundedText(
                repositoryRevision, "repositoryRevision", 512, false);
        sourceSnapshot = ContractChecks.requireNonNull(sourceSnapshot, "sourceSnapshot");
        inputSnapshot = ContractChecks.requireNonNull(inputSnapshot, "inputSnapshot");
        buildSnapshot = ContractChecks.requireNonNull(buildSnapshot, "buildSnapshot");
        completeness = ContractChecks.requireNonNull(completeness, "completeness");
        fingerprintSha256 = ContractChecks.requireSha256(fingerprintSha256, "fingerprintSha256");
        warnings = ContractChecks.immutableBoundedStrings(warnings, "warnings", 2_048);
        if (warnings.size() > 20) {
            throw new IllegalArgumentException("warnings 不能超过 20 条");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
        if (completeness == SnapshotCompleteness.COMPLETE
                && sourceSnapshot.completeness() != SnapshotCompleteness.COMPLETE) {
            throw new IllegalArgumentException("完整 Context 不能包含不完整源码快照");
        }
        if (completeness == SnapshotCompleteness.COMPLETE
                && inputSnapshot.status() == InputSnapshotStatus.UNRESOLVED) {
            throw new IllegalArgumentException("完整 Context 不能包含未解析输入");
        }
    }
}
