package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;

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
        List<String> artifactIds,
        EvidenceSourceCoverage sourceCoverage,
        List<String> reasonCodes,
        Optional<String> primaryArtifactId,
        String recommendedTool,
        List<EvidenceQueryMode> supportedModes,
        String nextAction) {

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
        sourceCoverage = sourceCoverage == null ? EvidenceSourceCoverage.UNKNOWN : sourceCoverage;
        reasonCodes = ContractChecks.immutableBoundedStrings(
                reasonCodes == null ? List.of() : reasonCodes, "reasonCodes", 128);
        if (reasonCodes.size() > 32) throw new IllegalArgumentException("reasonCodes exceeds the limit");
        primaryArtifactId = primaryArtifactId == null ? Optional.empty() : primaryArtifactId;
        primaryArtifactId = primaryArtifactId.map(id -> ContractChecks.requireOpaqueId(id, "primaryArtifactId"));
        if (primaryArtifactId.isPresent() && !artifactIds.contains(primaryArtifactId.orElseThrow())) {
            throw new IllegalArgumentException("primaryArtifactId must be present in artifactIds");
        }
        recommendedTool = ContractChecks.requireBoundedText(
                recommendedTool == null ? "" : recommendedTool, "recommendedTool", 64, true);
        supportedModes = ContractChecks.immutableList(
                supportedModes == null ? List.of() : supportedModes, "supportedModes");
        nextAction = ContractChecks.requireBoundedText(
                nextAction == null
                        ? "Inspect the returned collection facts before choosing another action"
                        : nextAction,
                "nextAction", 256, false);
    }

    /** 兼容未携带模型读取提示的历史调用方。 */
    public CollectionExecutionSummary(
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
        this(caseId, analysisId, runId, planId, collectionId, completion,
                baselineOutcome, evidenceUsable, artifactRelativePaths, artifactIds,
                evidenceUsable ? EvidenceSourceCoverage.COMPLETE : EvidenceSourceCoverage.UNKNOWN,
                List.of(), Optional.empty(), "", List.of(),
                "Inspect the returned collection facts before choosing another action");
    }
}
