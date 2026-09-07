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
        List<CodePathScopeCondition> scopeConditions,
        CodePathCaptureMode captureMode,
        int scopeStartOrdinal,
        int maxMatchedScopes,
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
        scopeConditions = ContractChecks.immutableList(scopeConditions, "scopeConditions");
        if (scopeConditions.size() > 4) {
            throw new IllegalArgumentException("scopeConditions must contain at most 4 conditions");
        }
        HashSet<String> conditionNames = new HashSet<>();
        if (scopeConditions.stream().anyMatch(condition ->
                !conditionNames.add(condition.projectionName()))) {
            throw new IllegalArgumentException("scopeConditions must not repeat a projectionName");
        }
        if (!scopeConditions.isEmpty() && scopeMethodKey.isEmpty()) {
            throw new IllegalArgumentException("scopeMethodKey is required when scopeConditions are present");
        }
        captureMode = ContractChecks.requireNonNull(captureMode, "captureMode");
        if (scopeStartOrdinal < 1 || scopeStartOrdinal > 1_000_000
                || maxMatchedScopes < 1 || maxMatchedScopes > 10_000) {
            throw new IllegalArgumentException("CodePath scope window is outside the safe range");
        }
        if (scopeMethodKey.isEmpty() && (scopeStartOrdinal != 1 || maxMatchedScopes != 10_000)) {
            throw new IllegalArgumentException("A non-default scope window requires scopeMethodKey");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        intent = ContractChecks.requireNonNull(intent, "intent");
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    /** 兼容无作用域条件的调用方；新计划始终序列化空的 scopeConditions。 */
    public CodePathCollectionPlan(
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
        this(schemaVersion, planId, caseId, analysisId, targetTest, methodSelections,
                scopeMethodKey, List.of(), CodePathCaptureMode.TRACE, 1, 10_000,
                budget, rationale, intent, createdAt);
    }

    /** 兼容 v5 调用方，默认使用逐调用 TRACE 和无限制的日常 Scope 窗口。 */
    public CodePathCollectionPlan(
            String schemaVersion,
            PlanId planId,
            CaseId caseId,
            AnalysisId analysisId,
            TargetTest targetTest,
            List<CodePathMethodSelection> methodSelections,
            Optional<String> scopeMethodKey,
            List<CodePathScopeCondition> scopeConditions,
            CollectionBudget budget,
            String rationale,
            InvestigationIntent intent,
            Instant createdAt) {
        this(schemaVersion, planId, caseId, analysisId, targetTest, methodSelections,
                scopeMethodKey, scopeConditions, CodePathCaptureMode.TRACE, 1, 10_000,
                budget, rationale, intent, createdAt);
    }
}
