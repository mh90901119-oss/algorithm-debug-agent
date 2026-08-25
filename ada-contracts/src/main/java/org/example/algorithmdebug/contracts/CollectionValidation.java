package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 一次 Collection 派生结果的统一技术可信度。 */
public record CollectionValidation(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        String collectorType,
        EvidenceValidationStatus status,
        List<ValidationFinding> findings,
        Set<EvidenceDimension> coveredDimensions,
        Optional<ArtifactReference> summaryArtifact,
        Instant validatedAt) {

    /** 校验身份、状态、Finding 和可覆盖维度。 */
    public CollectionValidation {
        if (!SchemaVersions.COLLECTION_VALIDATION.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 CollectionValidation schemaVersion");
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
        status = ContractChecks.requireNonNull(status, "status");
        findings = ContractChecks.immutableList(findings, "findings");
        if (findings.size() > 128) throw new IllegalArgumentException("findings 不能超过 128 项");
        coveredDimensions = EvidenceBuildRequest.immutableDimensions(
                coveredDimensions, "coveredDimensions");
        summaryArtifact = ContractChecks.requireNonNull(summaryArtifact, "summaryArtifact");
        validatedAt = ContractChecks.requireNonNull(validatedAt, "validatedAt");
        EvidenceDimension dynamic = "CODEPATH".equals(collectorType)
                ? EvidenceDimension.METHOD_PATH : EvidenceDimension.RUNTIME_STATE;
        if (status != EvidenceValidationStatus.VALID && coveredDimensions.contains(dynamic)) {
            throw new IllegalArgumentException("非 VALID Collection 不能覆盖动态证据维度");
        }
        if (status == EvidenceValidationStatus.VALID
                && (!coveredDimensions.contains(EvidenceDimension.VALIDATION)
                || !coveredDimensions.contains(dynamic) || summaryArtifact.isEmpty())) {
            throw new IllegalArgumentException("VALID Collection 必须覆盖验证和对应动态维度");
        }
    }
}
