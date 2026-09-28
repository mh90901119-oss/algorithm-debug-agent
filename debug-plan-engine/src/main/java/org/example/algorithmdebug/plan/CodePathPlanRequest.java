package org.example.algorithmdebug.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathScopeCondition;
import org.example.algorithmdebug.contracts.PlanId;

/** 大模型提交给确定性编译器的有界 CodePath 请求。 */
public record CodePathPlanRequest(
        PlanId planId,
        List<CodePathMethodRequest> methods,
        Optional<String> scopeMethodKey,
        List<CodePathScopeCondition> scopeConditions,
        CodePathCaptureMode captureMode,
        int scopeStartOrdinal,
        int maxMatchedScopes,
        String rationale,
        InvestigationBindingRequest investigation,
        CollectionBudget budget,
        Instant requestedAt) {

    public CodePathPlanRequest {
        planId = Objects.requireNonNull(planId, "planId");
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        scopeMethodKey = scopeMethodKey == null ? Optional.empty() : scopeMethodKey;
        scopeConditions = scopeConditions == null ? List.of() : List.copyOf(scopeConditions);
        if (scopeConditions.size() > 4) {
            throw new IllegalArgumentException("scopeConditions must contain at most 4 conditions");
        }
        captureMode = captureMode == null ? CodePathCaptureMode.TRACE : captureMode;
        scopeStartOrdinal = scopeStartOrdinal == 0 ? 1 : scopeStartOrdinal;
        maxMatchedScopes = maxMatchedScopes == 0 ? 10_000 : maxMatchedScopes;
        if (scopeStartOrdinal < 1 || scopeStartOrdinal > 1_000_000
                || maxMatchedScopes < 1 || maxMatchedScopes > 10_000) {
            throw new IllegalArgumentException("CodePath scope window is outside the safe range");
        }
        if (scopeMethodKey.isEmpty() && (scopeStartOrdinal != 1 || maxMatchedScopes != 10_000)) {
            throw new IllegalArgumentException("A non-default scope window requires scopeMethodKey");
        }
        rationale = Objects.requireNonNull(rationale, "rationale").strip();
        if (rationale.isEmpty() || rationale.length() > 4_096) {
            throw new IllegalArgumentException("rationale must contain between 1 and 4096 characters");
        }
        investigation = Objects.requireNonNull(investigation, "investigation");
        budget = Objects.requireNonNull(budget, "budget");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /** 兼容不需要运行时作用域过滤的计划请求。 */
    public CodePathPlanRequest(
            PlanId planId,
            List<CodePathMethodRequest> methods,
            Optional<String> scopeMethodKey,
            String rationale,
            InvestigationBindingRequest investigation,
            CollectionBudget budget,
            Instant requestedAt) {
        this(planId, methods, scopeMethodKey, List.of(), rationale, investigation, budget, requestedAt);
    }

    /** 兼容未声明模式和 Scope 窗口的调用方。 */
    public CodePathPlanRequest(
            PlanId planId,
            List<CodePathMethodRequest> methods,
            Optional<String> scopeMethodKey,
            List<CodePathScopeCondition> scopeConditions,
            String rationale,
            InvestigationBindingRequest investigation,
            CollectionBudget budget,
            Instant requestedAt) {
        this(planId, methods, scopeMethodKey, scopeConditions, CodePathCaptureMode.TRACE,
                1, 10_000, rationale, investigation, budget, requestedAt);
    }
}
