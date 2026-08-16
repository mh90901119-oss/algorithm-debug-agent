package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;

/**
 * 面向大模型的有界 Case 当前摘要，由不可变归档记录确定性重建。
 *
 * @param schemaVersion Schema 版本
 * @param caseId Case ID
 * @param projectId 项目 ID
 * @param targetTest 目标 UT
 * @param latestContextId 最新 Context；首次 Context 未提交时为空
 * @param latestAnalysisId 最新 Analysis；首次 Analysis 未提交时为空
 * @param latestQuestionExcerpt 最新问题的有界摘录
 * @param latestRunId 最新已完成 Run；没有完成 Run 时为空
 * @param recentRuns 最近最多 20 个完成 Run
 * @param incompleteRuns 最近最多 20 个只有 RunRequest 的 Run
 * @param archiveWarnings 最近最多 20 个子文档读取告警
 * @param contextCount Context 总数
 * @param analysisCount Analysis 总数
 * @param runCount Run 请求总数
 * @param truncated 是否有列表因预算截断
 */
public record CaseDigest(
        String schemaVersion,
        CaseId caseId,
        ProjectId projectId,
        TargetTest targetTest,
        Optional<ContextId> latestContextId,
        Optional<AnalysisId> latestAnalysisId,
        String latestQuestionExcerpt,
        Optional<RunId> latestRunId,
        List<RunOutcomeSummary> recentRuns,
        List<RunId> incompleteRuns,
        List<ArchiveWarning> archiveWarnings,
        int contextCount,
        int analysisCount,
        int runCount,
        boolean truncated) {

    /** 校验 Digest 身份、有界文本、计数和不可变集合。 */
    public CaseDigest {
        schemaVersion = CaseManifest.requireVersion(
                schemaVersion, SchemaVersions.CASE_DIGEST, "CaseDigest");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        projectId = ContractChecks.requireNonNull(projectId, "projectId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        latestContextId = ContractChecks.requireNonNull(latestContextId, "latestContextId");
        latestAnalysisId = ContractChecks.requireNonNull(latestAnalysisId, "latestAnalysisId");
        latestQuestionExcerpt = ContractChecks.requireBoundedText(
                latestQuestionExcerpt, "latestQuestionExcerpt", 2_048, false);
        latestRunId = ContractChecks.requireNonNull(latestRunId, "latestRunId");
        recentRuns = ContractChecks.immutableList(recentRuns, "recentRuns");
        incompleteRuns = ContractChecks.immutableList(incompleteRuns, "incompleteRuns");
        archiveWarnings = ContractChecks.immutableList(archiveWarnings, "archiveWarnings");
        if (contextCount < 0 || analysisCount < 0 || runCount < 0) {
            throw new IllegalArgumentException("CaseDigest 计数非法");
        }
        if ((contextCount == 0) != latestContextId.isEmpty()
                || (analysisCount == 0) != latestAnalysisId.isEmpty()) {
            throw new IllegalArgumentException("CaseDigest latest ID 与计数不一致");
        }
        if (recentRuns.size() > 20 || incompleteRuns.size() > 20 || archiveWarnings.size() > 20) {
            throw new IllegalArgumentException("CaseDigest 列表超过 20 项上限");
        }
    }
}
