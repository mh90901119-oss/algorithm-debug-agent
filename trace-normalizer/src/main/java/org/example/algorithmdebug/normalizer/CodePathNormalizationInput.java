package org.example.algorithmdebug.normalizer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
        String matchPrecision,
        boolean collectorTruncated,
        Instant createdAt) {

    /** 校验 Collection、Plan 和 Raw 输入身份。 */
    public CodePathNormalizationInput {
        if (collection == null || plan == null || rawTrace == null || rawTracePath == null
                || evidenceId == null || budget == null || matchPrecision == null
                || createdAt == null) {
            throw new IllegalArgumentException("CodePath 归一化输入不能为空");
        }
        if (!collection.caseId().equals(plan.caseId())
                || !collection.contextId().equals(plan.contextId())
                || !collection.analysisId().equals(plan.analysisId())
                || !collection.planId().equals(plan.planId())
                || !collection.targetTest().equals(plan.targetTest())) {
            throw new IllegalArgumentException("Collection 与 CodePath Plan 身份不一致");
        }
        if (!List.of("EXACT_DESCRIPTOR", "CLASS_METHOD_SUPERSET", "NONE")
                .contains(matchPrecision)) {
            throw new IllegalArgumentException("matchPrecision 非法");
        }
        if (rawTrace.sizeBytes() > budget.maxRawBytes()) {
            throw new IllegalArgumentException("Raw Trace 引用已超过归一化预算");
        }
        if (budget.maxMethods() < plan.selectors().size()) {
            throw new IllegalArgumentException("maxMethods 不能小于采集计划中的方法数");
        }
        rawTracePath = rawTracePath.toAbsolutePath().normalize();
    }
}
