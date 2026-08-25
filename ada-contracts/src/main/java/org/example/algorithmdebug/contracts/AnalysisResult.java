package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;

/**
 * 一轮 Analysis 的追加式完成结果。
 *
 * <p>该契约仅归档最终用户回答、分级结论和证据引用，不包含模型思维链。</p>
 *
 * @param schemaVersion Schema 版本
 * @param caseId Case ID
 * @param contextId 本轮 Context ID
 * @param analysisId Analysis ID
 * @param finalAnswer 面向用户的最终回答
 * @param conclusions 分级结论
 * @param referencedRunIds 本轮引用的 Run
 * @param referencedCollectionIds 本轮引用的 Collection
 * @param referencedEvidenceIds 本轮引用的 Evidence
 * @param referencedArtifactIds 本轮引用的 Artifact ID
 * @param missingEvidence 仍缺少的证据
 * @param completedAt 完成时间
 */
public record AnalysisResult(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        String finalAnswer,
        List<AnalysisConclusion> conclusions,
        List<RunId> referencedRunIds,
        List<CollectionId> referencedCollectionIds,
        List<EvidenceId> referencedEvidenceIds,
        List<String> referencedArtifactIds,
        List<String> missingEvidence,
        Instant completedAt) {

    /** 校验身份、有界最终内容和不可变引用集合。 */
    public AnalysisResult {
        if (!SchemaVersions.ANALYSIS_RESULT.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported AnalysisResult schemaVersion");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        finalAnswer = ContractChecks.requireBoundedText(finalAnswer, "finalAnswer", 65_536, false);
        conclusions = bounded(conclusions, "conclusions", 64);
        referencedRunIds = bounded(referencedRunIds, "referencedRunIds", 64);
        referencedCollectionIds = bounded(referencedCollectionIds, "referencedCollectionIds", 64);
        referencedEvidenceIds = bounded(referencedEvidenceIds, "referencedEvidenceIds", 64);
        referencedArtifactIds = ContractChecks.immutableNonBlankStrings(
                referencedArtifactIds, "referencedArtifactIds");
        if (referencedArtifactIds.size() > 64) {
            throw new IllegalArgumentException("referencedArtifactIds must not exceed 64 entries");
        }
        referencedArtifactIds.forEach(value ->
                ContractChecks.requireOpaqueId(value, "referencedArtifactId"));
        missingEvidence = ContractChecks.immutableBoundedStrings(
                missingEvidence, "missingEvidence", 2_048);
        if (missingEvidence.size() > 32) {
            throw new IllegalArgumentException("missingEvidence must not exceed 32 entries");
        }
        completedAt = ContractChecks.requireNonNull(completedAt, "completedAt");
    }

    private static <T> List<T> bounded(List<T> values, String field, int maximum) {
        List<T> copied = ContractChecks.immutableList(values, field);
        if (copied.size() > maximum) {
            throw new IllegalArgumentException(field + " must not exceed " + maximum + " entries");
        }
        return copied;
    }
}
