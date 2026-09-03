package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 一次 Raw Trace 派生的输入、预算、完成状态与失败事实。 */
public record NormalizationManifest(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
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
            throw new IllegalArgumentException("Unsupported NormalizationManifest schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        collectorType = ContractChecks.requireBoundedText(collectorType, "collectorType", 32, false);
        if (!List.of("CODEPATH", "JDWP").contains(collectorType)) {
            throw new IllegalArgumentException("collectorType is invalid");
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
            throw new IllegalArgumentException("Normalization counts must not be negative");
        }
        truncationReasons = ContractChecks.immutableBoundedStrings(
                truncationReasons, "truncationReasons", 2_048);
        if (truncationReasons.size() > 32) {
            throw new IllegalArgumentException("truncationReasons must not exceed 32 entries");
        }
        failureCode = ContractChecks.requireNonNull(failureCode, "failureCode")
                .map(value -> ContractChecks.requireBoundedText(value, "failureCode value", 128, false));
        failureDetail = ContractChecks.requireBoundedText(
                failureDetail, "failureDetail", 2_048, true);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
        if (status == NormalizationStatus.COMPLETE
                && (summaryArtifact.isEmpty() || !truncationReasons.isEmpty() || failureCode.isPresent())) {
            throw new IllegalArgumentException("COMPLETE must contain a summary and must not contain truncation or failure");
        }
        if (status == NormalizationStatus.PARTIAL
                && (summaryArtifact.isEmpty() || truncationReasons.isEmpty() || failureCode.isPresent())) {
            throw new IllegalArgumentException("PARTIAL must contain a summary and truncation reasons and must not contain a failure");
        }
        if (status == NormalizationStatus.FAILED
                && (summaryArtifact.isPresent() || failureCode.isEmpty())) {
            throw new IllegalArgumentException("FAILED must not contain a summary and must contain a failure code");
        }
    }
}
