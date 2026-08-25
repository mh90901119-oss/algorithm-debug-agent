package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;

/** 面向 Case Digest 的有界 Analysis 完成摘要。 */
public record AnalysisResultSummary(
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        String finalAnswerExcerpt,
        List<AnalysisConclusion> conclusions,
        List<RunId> referencedRunIds,
        List<CollectionId> referencedCollectionIds,
        List<EvidenceId> referencedEvidenceIds,
        List<String> missingEvidence,
        Instant completedAt) {

    /** 校验摘要身份和严格的小型列表预算。 */
    public AnalysisResultSummary {
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        finalAnswerExcerpt = ContractChecks.requireBoundedText(
                finalAnswerExcerpt, "finalAnswerExcerpt", 2_048, false);
        conclusions = bounded(conclusions, "conclusions", 5);
        referencedRunIds = bounded(referencedRunIds, "referencedRunIds", 20);
        referencedCollectionIds = bounded(
                referencedCollectionIds, "referencedCollectionIds", 20);
        referencedEvidenceIds = bounded(referencedEvidenceIds, "referencedEvidenceIds", 20);
        missingEvidence = ContractChecks.immutableBoundedStrings(
                missingEvidence, "missingEvidence", 2_048);
        if (missingEvidence.size() > 10) {
            throw new IllegalArgumentException("missingEvidence must not exceed 10 entries");
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
