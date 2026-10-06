package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationBindingStatus;

/** 只包含精确方法、显式标量投影、硬预算和不可变调查绑定的 CodePath 采集计划。 */
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
        String questionToAnswer,
        InvestigationBindingStatus investigationStatus,
        Optional<InvestigationBinding> investigationBinding,
        Instant createdAt) {

    private static final int MAX_METHOD_SELECTIONS = 50;
    private static final int MAX_SCOPE_CONDITIONS = 4;
    private static final int MAX_SCOPE_ORDINAL = 1_000_000;
    private static final int DEFAULT_MAX_MATCHED_SCOPES = 10_000;

    /** 校验身份、方法唯一性、硬上限、Scope 成员关系和调查绑定版本语义。 */
    public CodePathCollectionPlan {
        boolean current = SchemaVersions.CODEPATH_COLLECTION_PLAN.equals(schemaVersion);
        boolean legacy = SchemaVersions.CODEPATH_COLLECTION_PLAN_LEGACY.equals(schemaVersion);
        if (!current && !legacy) {
            throw new IllegalArgumentException("Unsupported CodePathCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        methodSelections = ContractChecks.immutableList(methodSelections, "methodSelections");
        if (methodSelections.isEmpty() || methodSelections.size() > MAX_METHOD_SELECTIONS) {
            throw new IllegalArgumentException("methodSelections count must be between 1 and 50");
        }
        HashSet<String> keys = new HashSet<>();
        if (methodSelections.stream().anyMatch(
                selection -> !keys.add(selection.selector().methodKey()))) {
            throw new IllegalArgumentException("methodSelections must not contain duplicate methodKey");
        }
        scopeMethodKey = scopeMethodKey == null ? Optional.empty() : scopeMethodKey;
        scopeMethodKey = scopeMethodKey.map(value ->
                ContractChecks.requireBoundedText(value, "scopeMethodKey", 2_048, false));
        if (scopeMethodKey.isPresent() && !keys.contains(scopeMethodKey.orElseThrow())) {
            throw new IllegalArgumentException("scopeMethodKey must also be selected");
        }
        scopeConditions = ContractChecks.immutableList(scopeConditions, "scopeConditions");
        if (scopeConditions.size() > MAX_SCOPE_CONDITIONS) {
            throw new IllegalArgumentException("scopeConditions must contain at most 4 conditions");
        }
        HashSet<String> conditionNames = new HashSet<>();
        if (scopeConditions.stream().anyMatch(
                condition -> !conditionNames.add(condition.projectionName()))) {
            throw new IllegalArgumentException("scopeConditions must not repeat a projectionName");
        }
        if (!scopeConditions.isEmpty() && scopeMethodKey.isEmpty()) {
            throw new IllegalArgumentException("scopeMethodKey is required when scopeConditions are present");
        }
        captureMode = ContractChecks.requireNonNull(captureMode, "captureMode");
        if (scopeStartOrdinal < 1 || scopeStartOrdinal > MAX_SCOPE_ORDINAL
                || maxMatchedScopes < 1 || maxMatchedScopes > DEFAULT_MAX_MATCHED_SCOPES) {
            throw new IllegalArgumentException("CodePath scope window is outside the safe range");
        }
        if (scopeMethodKey.isEmpty()
                && (scopeStartOrdinal != 1 || maxMatchedScopes != DEFAULT_MAX_MATCHED_SCOPES)) {
            throw new IllegalArgumentException("A non-default scope window requires scopeMethodKey");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        questionToAnswer = ContractChecks.requireBoundedText(
                questionToAnswer, "questionToAnswer", 2_048, false);
        investigationStatus = ContractChecks.requireNonNull(
                investigationStatus, "investigationStatus");
        investigationBinding = investigationBinding == null
                ? Optional.empty() : investigationBinding;
        validateInvestigation(current, caseId, analysisId,
                investigationStatus, investigationBinding);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 同时读取当前 v7 与旧 v6 JSON。旧 intent 只投影 questionToAnswer，不能恢复机器门禁。
     */
    @JsonCreator
    public static CodePathCollectionPlan fromJson(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("planId") PlanId planId,
            @JsonProperty("caseId") CaseId caseId,
            @JsonProperty("analysisId") AnalysisId analysisId,
            @JsonProperty("targetTest") TargetTest targetTest,
            @JsonProperty("methodSelections") List<CodePathMethodSelection> methodSelections,
            @JsonProperty("scopeMethodKey") Optional<String> scopeMethodKey,
            @JsonProperty("scopeConditions") List<CodePathScopeCondition> scopeConditions,
            @JsonProperty("captureMode") CodePathCaptureMode captureMode,
            @JsonProperty("scopeStartOrdinal") int scopeStartOrdinal,
            @JsonProperty("maxMatchedScopes") int maxMatchedScopes,
            @JsonProperty("budget") CollectionBudget budget,
            @JsonProperty("rationale") String rationale,
            @JsonProperty("questionToAnswer") String questionToAnswer,
            @JsonProperty("investigationStatus") InvestigationBindingStatus investigationStatus,
            @JsonProperty("investigationBinding") Optional<InvestigationBinding> investigationBinding,
            @JsonProperty("intent") InvestigationIntent legacyIntent,
            @JsonProperty("createdAt") Instant createdAt) {
        if (SchemaVersions.CODEPATH_COLLECTION_PLAN_LEGACY.equals(schemaVersion)) {
            if (legacyIntent == null || questionToAnswer != null || investigationStatus != null
                    || (investigationBinding != null && investigationBinding.isPresent())) {
                throw new IllegalArgumentException("Legacy CodePath plan must contain only legacy intent");
            }
            return new CodePathCollectionPlan(
                    schemaVersion, planId, caseId, analysisId, targetTest, methodSelections,
                    scopeMethodKey, scopeConditions, captureMode, scopeStartOrdinal,
                    maxMatchedScopes, budget, rationale, legacyIntent.questionToAnswer(),
                    InvestigationBindingStatus.LEGACY_UNSTRUCTURED, Optional.empty(), createdAt);
        }
        if (legacyIntent != null) {
            throw new IllegalArgumentException("Current CodePath plan must not contain legacy intent");
        }
        return new CodePathCollectionPlan(
                schemaVersion, planId, caseId, analysisId, targetTest, methodSelections,
                scopeMethodKey, scopeConditions, captureMode, scopeStartOrdinal,
                maxMatchedScopes, budget, rationale, questionToAnswer,
                investigationStatus, investigationBinding, createdAt);
    }

    /** 旧 v6 Java 调用方只读构造器；不能用于创建当前计划。 */
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
                scopeMethodKey, List.of(), CodePathCaptureMode.TRACE, 1,
                DEFAULT_MAX_MATCHED_SCOPES, budget, rationale, legacyQuestion(intent),
                InvestigationBindingStatus.LEGACY_UNSTRUCTURED, Optional.empty(), createdAt);
    }

    /** 旧 v6 作用域调用方只读构造器。 */
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
                scopeMethodKey, scopeConditions, CodePathCaptureMode.TRACE, 1,
                DEFAULT_MAX_MATCHED_SCOPES, budget, rationale, legacyQuestion(intent),
                InvestigationBindingStatus.LEGACY_UNSTRUCTURED, Optional.empty(), createdAt);
    }

    /** 旧 v6 完整调用方只读构造器。 */
    public CodePathCollectionPlan(
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
        this(schemaVersion, planId, caseId, analysisId, targetTest, methodSelections,
                scopeMethodKey, scopeConditions, captureMode, scopeStartOrdinal,
                maxMatchedScopes, budget, rationale, legacyQuestion(intent),
                InvestigationBindingStatus.LEGACY_UNSTRUCTURED, Optional.empty(), createdAt);
    }

    private static String legacyQuestion(InvestigationIntent intent) {
        return ContractChecks.requireNonNull(intent, "intent").questionToAnswer();
    }

    private static void validateInvestigation(
            boolean current,
            CaseId caseId,
            AnalysisId analysisId,
            InvestigationBindingStatus status,
            Optional<InvestigationBinding> binding) {
        if (current) {
            if (status != InvestigationBindingStatus.STRUCTURED || binding.isEmpty()) {
                throw new IllegalArgumentException(
                        "Current CodePath plan requires a STRUCTURED InvestigationBinding");
            }
            InvestigationBinding value = binding.orElseThrow();
            if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())) {
                throw new IllegalArgumentException("CodePath plan binding identity mismatch");
            }
        } else if (status != InvestigationBindingStatus.LEGACY_UNSTRUCTURED
                || binding.isPresent()) {
            throw new IllegalArgumentException(
                    "Legacy CodePath plan must be LEGACY_UNSTRUCTURED without a binding");
        }
    }
}
