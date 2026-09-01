package org.example.algorithmdebug.normalizer;

import java.nio.file.Path;
import java.time.Instant;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.NormalizationBudget;

/** CodePath 归一化所需的已归档身份、计划、Raw 引用和确定性预算。 */
public record CodePathNormalizationInput(
        MethodPathCollectionRecord collection,
        CodePathCollectionPlan plan,
        ArtifactReference rawTrace,
        Path rawTracePath,
        EvidenceId evidenceId,
        NormalizationBudget budget,
        boolean collectorTruncated,
        Instant createdAt) {

    /** 校验 Collection、Plan 和 Raw 输入身份。 */
    public CodePathNormalizationInput {
        if (collection == null || plan == null || rawTrace == null || rawTracePath == null
                || evidenceId == null || budget == null
                || createdAt == null) {
            throw new IllegalArgumentException("CodePath normalization input must not be null");
        }
        if (!collection.caseId().equals(plan.caseId())
                || !collection.contextId().equals(plan.contextId())
                || !collection.analysisId().equals(plan.analysisId())
                || !collection.planId().equals(plan.planId())
                || !collection.targetTest().equals(plan.targetTest())) {
            throw new IllegalArgumentException("Collection does not match CodePath Plan identity");
        }
        if (rawTrace.sizeBytes() > budget.maxRawBytes()) {
            throw new IllegalArgumentException("Raw Trace references exceed the normalization budget");
        }
        if (budget.maxMethods() < plan.selectors().size()) {
            throw new IllegalArgumentException("maxMethods must not be less than the method count in the collection plan");
        }
        rawTracePath = rawTracePath.toAbsolutePath().normalize();
    }
}
