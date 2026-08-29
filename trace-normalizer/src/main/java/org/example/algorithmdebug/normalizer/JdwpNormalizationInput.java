package org.example.algorithmdebug.normalizer;

import java.nio.file.Path;
import java.time.Instant;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.NormalizationBudget;

/** JDWP 归一化所需的归档身份、计划、Raw 引用和确定性预算。 */
public record JdwpNormalizationInput(
        JdwpCollectionRecord collection,
        JdwpCollectionPlan plan,
        ArtifactReference rawTrace,
        Path rawTracePath,
        EvidenceId evidenceId,
        NormalizationBudget budget,
        boolean collectorTruncated,
        Instant createdAt) {

    /** 校验 Collection、Plan 与 Raw Artifact 的身份和预算边界。 */
    public JdwpNormalizationInput {
        if (collection == null || plan == null || rawTrace == null || rawTracePath == null
                || evidenceId == null || budget == null || createdAt == null) {
            throw new IllegalArgumentException("JDWP normalization input must not be null");
        }
        if (!collection.caseId().equals(plan.caseId())
                || !collection.contextId().equals(plan.contextId())
                || !collection.analysisId().equals(plan.analysisId())
                || !collection.planId().equals(plan.planId())
                || !collection.targetTest().equals(plan.targetTest())) {
            throw new IllegalArgumentException("Collection does not match JDWP Plan identity");
        }
        if (rawTrace.sizeBytes() > budget.maxRawBytes()) {
            throw new IllegalArgumentException("JDWP Raw Trace references exceed the normalization budget");
        }
        rawTracePath = rawTracePath.toAbsolutePath().normalize();
    }
}
