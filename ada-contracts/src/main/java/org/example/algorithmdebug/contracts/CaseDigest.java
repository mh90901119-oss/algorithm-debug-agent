package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;

/**
 * 面向大模型的有界 Case 摘要，由不可变归档记录确定性重建。
 *
 * @param schemaVersion Schema 版本
 * @param caseId Case ID
 * @param projectId 项目 ID
 * @param targetTest 目标 UT
 * @param latestAnalysisId 最新 Analysis；尚未创建时为空
 * @param latestQuestionExcerpt 最新问题的有界摘录
 * @param latestRunId 最新已完成目标 Run；没有时为空
 * @param recentRuns 最近最多 20 个已完成 Run
 * @param incompleteRuns 最近最多 20 个未完成 Run ID
 * @param recentCollections 最近最多 20 个已完成 Collection
 * @param recentEvidence 最近最多 20 个 Evidence 充分性结果
 * @param archiveWarnings 最近最多 20 个归档读取警告
 * @param analysisCount Analysis 总数
 * @param runCount Run 请求总数
 * @param collectionCount Collection 请求总数
 * @param evidenceCount Evidence 请求总数
 * @param truncated 是否有列表因预算截断
 */
public record CaseDigest(
        String schemaVersion,
        CaseId caseId,
        ProjectId projectId,
        TargetTest targetTest,
        Optional<AnalysisId> latestAnalysisId,
        String latestQuestionExcerpt,
        Optional<RunId> latestRunId,
        List<RunOutcomeSummary> recentRuns,
        List<RunId> incompleteRuns,
        List<CollectionExecutionSummary> recentCollections,
        List<SufficiencyEvaluation> recentEvidence,
        List<ArchiveWarning> archiveWarnings,
        int analysisCount,
        int runCount,
        int collectionCount,
        int evidenceCount,
        boolean truncated) {

    /** 校验身份、列表预算、计数关系和不可变集合。 */
    public CaseDigest {
        schemaVersion = CaseManifest.requireVersion(
                schemaVersion, SchemaVersions.CASE_DIGEST, "CaseDigest");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        projectId = ContractChecks.requireNonNull(projectId, "projectId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        latestAnalysisId = ContractChecks.requireNonNull(latestAnalysisId, "latestAnalysisId");
        latestQuestionExcerpt = ContractChecks.requireBoundedText(
                latestQuestionExcerpt, "latestQuestionExcerpt", 2_048, false);
        latestRunId = ContractChecks.requireNonNull(latestRunId, "latestRunId");
        recentRuns = ContractChecks.immutableList(recentRuns, "recentRuns");
        incompleteRuns = ContractChecks.immutableList(incompleteRuns, "incompleteRuns");
        recentCollections = ContractChecks.immutableList(recentCollections, "recentCollections");
        recentEvidence = ContractChecks.immutableList(recentEvidence, "recentEvidence");
        archiveWarnings = ContractChecks.immutableList(archiveWarnings, "archiveWarnings");
        if (analysisCount < 0 || runCount < 0 || collectionCount < 0 || evidenceCount < 0) {
            throw new IllegalArgumentException("CaseDigest counts are invalid");
        }
        if ((analysisCount == 0) != latestAnalysisId.isEmpty()) {
            throw new IllegalArgumentException("CaseDigest latest Analysis ID does not match counts");
        }
        if (recentRuns.size() > 20 || incompleteRuns.size() > 20
                || recentCollections.size() > 20 || recentEvidence.size() > 20
                || archiveWarnings.size() > 20) {
            throw new IllegalArgumentException("CaseDigest list exceeds the 20-entry limit");
        }
    }
}
