package org.example.algorithmdebug.contracts;

import java.time.Instant;

/** 外部 JDWP Collector 启动前写入 Case 的不可变请求身份。 */
public record JdwpCollectionRecord(
        String schemaVersion,
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        TargetTest targetTest,
        String collectorType,
        Instant createdAt) {

    /** 校验版本、执行身份和固定 Collector 类型。 */
    public JdwpCollectionRecord {
        if (!SchemaVersions.JDWP_COLLECTION_REQUEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported JdwpCollectionRecord schemaVersion");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        if (!"JDWP".equals(collectorType)) {
            throw new IllegalArgumentException("collectorType must be JDWP");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
