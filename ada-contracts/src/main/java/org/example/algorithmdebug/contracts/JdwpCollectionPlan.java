package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** 可归档、可确定性编译的 JDWP v4 采集计划。 */
public record JdwpCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<JdwpTracepointSpec> tracepoints,
        JdwpCollectionBudget budget,
        String rationale,
        InvestigationIntent intent,
        Instant createdAt) {

    public JdwpCollectionPlan {
        if (!SchemaVersions.JDWP_COLLECTION_PLAN.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported JdwpCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
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
        budget = ContractChecks.requireNonNull(budget, "budget");
        long maximumSnapshots = tracepoints.stream()
                .mapToLong(JdwpTracepointSpec::maxCapturedHits).sum();
        if (maximumSnapshots > budget.maxEvents() || maximumSnapshots > 500) {
            throw new IllegalArgumentException("Tracepoint snapshots exceed the collection event budget");
        }
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        intent = ContractChecks.requireNonNull(intent, "intent");
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
