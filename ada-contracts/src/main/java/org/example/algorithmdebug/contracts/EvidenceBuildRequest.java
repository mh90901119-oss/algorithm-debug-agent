package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一次不可变 Evidence 派生的显式输入。 */
public record EvidenceBuildRequest(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        List<CollectionId> collectionIds,
        List<CollectionId> comparisonCollectionIds,
        Set<EvidenceDimension> requiredDimensions,
        long maxSummaryBytes,
        long maxEvidenceBundleBytes,
        Instant createdAt) {

    /** 校验身份、Collection 角色、要求维度和输出预算。 */
    public EvidenceBuildRequest {
        if (!SchemaVersions.EVIDENCE_BUILD_REQUEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported EvidenceBuildRequest schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        collectionIds = boundedIds(collectionIds, "collectionIds");
        comparisonCollectionIds = boundedIds(comparisonCollectionIds, "comparisonCollectionIds");
        HashSet<CollectionId> overlap = new HashSet<>(collectionIds);
        overlap.retainAll(comparisonCollectionIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("The same Collection must not be both current and comparison evidence");
        }
        requiredDimensions = immutableDimensions(requiredDimensions, "requiredDimensions");
        if (requiredDimensions.isEmpty()) {
            throw new IllegalArgumentException("requiredDimensions must not be null");
        }
        if (maxSummaryBytes < 1 || maxSummaryBytes > NormalizationBudget.MAX_SUMMARY_BYTES) {
            throw new IllegalArgumentException("maxSummaryBytes exceeds the P4 hard limit");
        }
        if (maxEvidenceBundleBytes < 1 || maxEvidenceBundleBytes > 1024L * 1024) {
            throw new IllegalArgumentException("maxEvidenceBundleBytes exceeds the P4 hard limit");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    private static List<CollectionId> boundedIds(List<CollectionId> values, String field) {
        List<CollectionId> copied = ContractChecks.immutableList(values, field);
        if (copied.size() > 16 || new HashSet<>(copied).size() != copied.size()) {
            throw new IllegalArgumentException(field + " count or uniqueness is invalid");
        }
        return copied;
    }

    static Set<EvidenceDimension> immutableDimensions(
            Set<EvidenceDimension> values, String field) {
        ContractChecks.requireNonNull(values, field);
        if (values.stream().anyMatch(java.util.Objects::isNull) || values.size() > 7) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return Set.copyOf(values);
    }
}
