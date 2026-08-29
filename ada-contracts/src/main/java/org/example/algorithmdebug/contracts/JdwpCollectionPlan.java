package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** 可归档、可确定性编译的 JDWP v2 采集计划；每个采集点保留精确 SourceAnchor。 */
public record JdwpCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<JdwpTracepointSpec> tracepoints,
        JdwpCollectionBudget budget,
        String rationale,
        Instant createdAt) {

    /** 校验身份、采集点唯一性及 locals 的保守限制。 */
    public JdwpCollectionPlan {
        if (!SchemaVersions.JDWP_COLLECTION_PLAN.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported JdwpCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        tracepoints = ContractChecks.immutableList(tracepoints, "tracepoints");
        if (tracepoints.isEmpty() || tracepoints.size() > 20) {
            throw new IllegalArgumentException("tracepoints count must be between 1 and 20");
        }
        HashSet<String> ids = new HashSet<>();
        if (tracepoints.stream().anyMatch(point -> !ids.add(point.tracepointId()))) {
            throw new IllegalArgumentException("tracepointId must not be duplicated");
        }
        long localsPoints = tracepoints.stream().filter(point -> point.capture().locals()).count();
        if (localsPoints > 5 || tracepoints.stream().anyMatch(point ->
                point.capture().locals() && point.maxHits() > 5)) {
            throw new IllegalArgumentException("locals collection is outside the conservative range");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
