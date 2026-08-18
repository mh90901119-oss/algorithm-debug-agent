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
        List<CollectionId> collectionIds,
        List<CollectionId> comparisonCollectionIds,
        Set<EvidenceDimension> requiredDimensions,
        long maxSummaryBytes,
        long maxEvidenceBundleBytes,
        Instant createdAt) {

    /** 校验身份、Collection 角色、要求维度和输出预算。 */
    public EvidenceBuildRequest {
        if (!SchemaVersions.EVIDENCE_BUILD_REQUEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 EvidenceBuildRequest schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        collectionIds = boundedIds(collectionIds, "collectionIds");
        comparisonCollectionIds = boundedIds(comparisonCollectionIds, "comparisonCollectionIds");
        HashSet<CollectionId> overlap = new HashSet<>(collectionIds);
        overlap.retainAll(comparisonCollectionIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("同一 Collection 不能同时作为当前证据和比较证据");
        }
        requiredDimensions = immutableDimensions(requiredDimensions, "requiredDimensions");
        if (requiredDimensions.isEmpty()) {
            throw new IllegalArgumentException("requiredDimensions 不能为空");
        }
        if (maxSummaryBytes < 1 || maxSummaryBytes > NormalizationBudget.MAX_SUMMARY_BYTES) {
            throw new IllegalArgumentException("maxSummaryBytes 超出 P4 硬上限");
        }
        if (maxEvidenceBundleBytes < 1 || maxEvidenceBundleBytes > 1024L * 1024) {
            throw new IllegalArgumentException("maxEvidenceBundleBytes 超出 P4 硬上限");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    private static List<CollectionId> boundedIds(List<CollectionId> values, String field) {
        List<CollectionId> copied = ContractChecks.immutableList(values, field);
        if (copied.size() > 16 || new HashSet<>(copied).size() != copied.size()) {
            throw new IllegalArgumentException(field + " 数量或唯一性非法");
        }
        return copied;
    }

    static Set<EvidenceDimension> immutableDimensions(
            Set<EvidenceDimension> values, String field) {
        ContractChecks.requireNonNull(values, field);
        if (values.stream().anyMatch(java.util.Objects::isNull) || values.size() > 7) {
            throw new IllegalArgumentException(field + " 非法");
        }
        return Set.copyOf(values);
    }
}
