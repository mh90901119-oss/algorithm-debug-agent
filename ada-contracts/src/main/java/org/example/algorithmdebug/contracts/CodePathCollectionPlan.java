package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** 只包含精确方法选择器和硬预算的 CodePath v2 采集计划。 */
public record CodePathCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<MethodSelector> selectors,
        CollectionBudget budget,
        String rationale,
        Instant createdAt) {

    /** 校验身份、选择器唯一性、硬上限和解释文本。 */
    public CodePathCollectionPlan {
        if (!SchemaVersions.CODEPATH_COLLECTION_PLAN.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 CodePathCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        selectors = ContractChecks.immutableList(selectors, "selectors");
        if (selectors.isEmpty() || selectors.size() > 50) {
            throw new IllegalArgumentException("selectors 数量必须在 1 到 50 之间");
        }
        HashSet<String> keys = new HashSet<>();
        if (selectors.stream().anyMatch(selector -> !keys.add(selector.methodKey()))) {
            throw new IllegalArgumentException("selectors 不得包含重复 methodKey");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
