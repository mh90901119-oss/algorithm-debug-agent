package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 一次 Raw Trace 派生的输入、预算、完成状态与失败事实。 */
public record NormalizationManifest(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        String collectorType,
        String normalizerName,
        String normalizerVersion,
        NormalizationStatus status,
        ArtifactReference rawArtifact,
        Optional<ArtifactReference> summaryArtifact,
        NormalizationBudget budget,
        long inputRecordCount,
        long emittedFactCount,
        List<String> truncationReasons,
        Optional<String> failureCode,
        String failureDetail,
        Instant createdAt) {

    /** 校验完成、部分和失败状态与产物的一致性。 */
    public NormalizationManifest {
        if (!SchemaVersions.NORMALIZATION_MANIFEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 NormalizationManifest schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        collectorType = ContractChecks.requireBoundedText(collectorType, "collectorType", 32, false);
        if (!List.of("CODEPATH", "JDWP").contains(collectorType)) {
            throw new IllegalArgumentException("collectorType 非法");
        }
        normalizerName = ContractChecks.requireBoundedText(
                normalizerName, "normalizerName", 128, false);
        normalizerVersion = ContractChecks.requireBoundedText(
                normalizerVersion, "normalizerVersion", 64, false);
        status = ContractChecks.requireNonNull(status, "status");
        rawArtifact = ContractChecks.requireNonNull(rawArtifact, "rawArtifact");
        summaryArtifact = ContractChecks.requireNonNull(summaryArtifact, "summaryArtifact");
        budget = ContractChecks.requireNonNull(budget, "budget");
        if (inputRecordCount < 0 || emittedFactCount < 0) {
            throw new IllegalArgumentException("归一化计数不能为负数");
        }
        truncationReasons = ContractChecks.immutableBoundedStrings(
                truncationReasons, "truncationReasons", 2_048);
        if (truncationReasons.size() > 32) {
            throw new IllegalArgumentException("truncationReasons 不能超过 32 项");
        }
        failureCode = ContractChecks.requireNonNull(failureCode, "failureCode")
                .map(value -> ContractChecks.requireBoundedText(value, "failureCode value", 128, false));
        failureDetail = ContractChecks.requireBoundedText(
                failureDetail, "failureDetail", 2_048, true);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
        if (status == NormalizationStatus.COMPLETE
                && (summaryArtifact.isEmpty() || !truncationReasons.isEmpty() || failureCode.isPresent())) {
            throw new IllegalArgumentException("COMPLETE 必须有摘要且不能包含截断或失败");
        }
        if (status == NormalizationStatus.PARTIAL
                && (summaryArtifact.isEmpty() || truncationReasons.isEmpty() || failureCode.isPresent())) {
            throw new IllegalArgumentException("PARTIAL 必须有摘要和截断原因且不能包含失败");
        }
        if (status == NormalizationStatus.FAILED
                && (summaryArtifact.isPresent() || failureCode.isEmpty())) {
            throw new IllegalArgumentException("FAILED 不得有摘要且必须包含失败代码");
        }
    }
}
