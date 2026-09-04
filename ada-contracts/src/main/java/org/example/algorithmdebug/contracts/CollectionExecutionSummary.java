package org.example.algorithmdebug.contracts;

import java.util.List;

/** 面向 CLI/模型的有界动态采集摘要，不泄露本机绝对路径。 */
public record CollectionExecutionSummary(
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        String completion,
        ComparisonOutcome baselineOutcome,
        boolean evidenceUsable,
        List<String> artifactRelativePaths,
        List<String> artifactIds) {

    /** 校验身份、状态和有界相对产物路径。 */
    public CollectionExecutionSummary {
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        completion = ContractChecks.requireBoundedText(completion, "completion", 64, false);
        baselineOutcome = ContractChecks.requireNonNull(baselineOutcome, "baselineOutcome");
        if (evidenceUsable && (baselineOutcome == ComparisonOutcome.CHANGED
                || baselineOutcome == ComparisonOutcome.INCOMPARABLE)) {
            throw new IllegalArgumentException(
                    "Changed or incomparable collection evidence is not usable");
        }
        artifactRelativePaths = ContractChecks.immutableBoundedStrings(
                artifactRelativePaths, "artifactRelativePaths", 1_024);
        if (artifactRelativePaths.size() > 32) {
            throw new IllegalArgumentException("artifactRelativePaths must not exceed 32 entries");
        }
        artifactRelativePaths.forEach(path ->
                ContractChecks.requirePortableRelativePath(path, "artifactRelativePath"));
        artifactIds = artifactIds == null ? List.of()
                : ContractChecks.immutableNonBlankStrings(artifactIds, "artifactIds");
        if (artifactIds.size() > 32) {
            throw new IllegalArgumentException("artifactIds must not exceed 32 entries");
        }
        artifactIds.forEach(id -> ContractChecks.requireOpaqueId(id, "artifactId"));
    }
}
