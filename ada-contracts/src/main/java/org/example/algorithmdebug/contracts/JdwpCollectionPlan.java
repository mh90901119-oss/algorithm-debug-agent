package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * 可归档、可确定性编译的 JDWP 采集计划。
 *
 * <p>该 v1 契约只表达锁定 Collector 已实现的能力，不包含变量白名单、字段投影或采样。</p>
 */
public record JdwpCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        String sourceFingerprintSha256,
        List<JdwpTracepointSpec> tracepoints,
        JdwpCollectionBudget budget,
        String rationale,
        Instant createdAt) {

    /** 校验身份、唯一采集点、全局预算和 locals 的额外保守限制。 */
    public JdwpCollectionPlan {
        if (!SchemaVersions.JDWP_COLLECTION_PLAN.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 JdwpCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        sourceFingerprintSha256 = ContractChecks.requireSha256(
                sourceFingerprintSha256, "sourceFingerprintSha256");
        tracepoints = ContractChecks.immutableList(tracepoints, "tracepoints");
        if (tracepoints.isEmpty() || tracepoints.size() > 20) {
            throw new IllegalArgumentException("tracepoints 数量必须在 1 到 20 之间");
        }
        HashSet<String> ids = new HashSet<>();
        if (tracepoints.stream().anyMatch(point -> !ids.add(point.tracepointId()))) {
            throw new IllegalArgumentException("tracepointId 不得重复");
        }
        long localsPoints = tracepoints.stream().filter(point -> point.capture().locals()).count();
        if (localsPoints > 5 || tracepoints.stream().anyMatch(point ->
                point.capture().locals() && point.maxHits() > 5)) {
            throw new IllegalArgumentException("locals 采集超出 P3 保守命中范围");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
