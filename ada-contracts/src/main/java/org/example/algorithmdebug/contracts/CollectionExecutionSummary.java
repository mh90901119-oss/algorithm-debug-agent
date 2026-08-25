package org.example.algorithmdebug.contracts;

import java.util.List;

/** 面向 CLI/模型的有界动态采集摘要，不泄露本机绝对路径。 */
public record CollectionExecutionSummary(
        CaseId caseId,
        ContextId contextId,
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
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
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
            throw new IllegalArgumentException("artifactRelativePaths 不能超过 32 项");
        }
        artifactRelativePaths.forEach(path ->
                ContractChecks.requirePortableRelativePath(path, "artifactRelativePath"));
        artifactIds = artifactIds == null ? List.of()
                : ContractChecks.immutableNonBlankStrings(artifactIds, "artifactIds");
        if (artifactIds.size() > 32) {
            throw new IllegalArgumentException("artifactIds 不能超过 32 项");
        }
        artifactIds.forEach(id -> ContractChecks.requireOpaqueId(id, "artifactId"));
    }
}
