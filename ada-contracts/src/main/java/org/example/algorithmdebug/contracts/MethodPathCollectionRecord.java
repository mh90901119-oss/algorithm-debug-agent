package org.example.algorithmdebug.contracts;

import java.time.Instant;

/**
 * 外部 Collector 启动前写入 Case 的不可变采集请求身份。
 */
public record MethodPathCollectionRecord(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        TargetTest targetTest,
        String collectorType,
        Instant createdAt) {

    /** 校验版本、身份和 Collector 类型。 */
    public MethodPathCollectionRecord {
        if (!"1.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported MethodPathCollectionRecord version");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        collectorType = ContractChecks.requireBoundedText(
                collectorType, "collectorType", 128, false);
        if (!"CODEPATH".equals(collectorType)) {
            throw new IllegalArgumentException("The current collection record only supports CODEPATH");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
