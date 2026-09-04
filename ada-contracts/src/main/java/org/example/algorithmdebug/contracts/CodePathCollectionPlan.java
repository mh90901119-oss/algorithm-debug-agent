package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** 只包含精确方法、显式标量投影和硬预算的 CodePath 采集计划。 */
public record CodePathCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<CodePathMethodSelection> methodSelections,
        Optional<String> scopeMethodKey,
        CollectionBudget budget,
        String rationale,
        InvestigationIntent intent,
        Instant createdAt) {

    /** 校验身份、方法唯一性、硬上限和 Scope 成员关系。 */
    public CodePathCollectionPlan {
        if (!SchemaVersions.CODEPATH_COLLECTION_PLAN.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported CodePathCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        methodSelections = ContractChecks.immutableList(methodSelections, "methodSelections");
        if (methodSelections.isEmpty() || methodSelections.size() > 50) {
            throw new IllegalArgumentException("methodSelections count must be between 1 and 50");
        }
        HashSet<String> keys = new HashSet<>();
        if (methodSelections.stream().anyMatch(selection -> !keys.add(selection.selector().methodKey()))) {
            throw new IllegalArgumentException("methodSelections must not contain duplicate methodKey");
        }
        scopeMethodKey = scopeMethodKey == null ? Optional.empty() : scopeMethodKey;
        scopeMethodKey = scopeMethodKey.map(value ->
                ContractChecks.requireBoundedText(value, "scopeMethodKey", 2_048, false));
        if (scopeMethodKey.isPresent() && !keys.contains(scopeMethodKey.orElseThrow())) {
            throw new IllegalArgumentException("scopeMethodKey must also be selected");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        intent = ContractChecks.requireNonNull(intent, "intent");
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
