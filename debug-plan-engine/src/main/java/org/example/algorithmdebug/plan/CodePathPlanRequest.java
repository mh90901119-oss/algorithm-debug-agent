package org.example.algorithmdebug.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.InvestigationIntent;
import org.example.algorithmdebug.contracts.PlanId;

/** 大模型提交给确定性编译器的有界 CodePath 请求。 */
public record CodePathPlanRequest(
        PlanId planId,
        List<CodePathMethodRequest> methods,
        Optional<String> scopeMethodKey,
        String rationale,
        InvestigationIntent intent,
        CollectionBudget budget,
        Instant requestedAt) {

    public CodePathPlanRequest {
        planId = Objects.requireNonNull(planId, "planId");
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        scopeMethodKey = scopeMethodKey == null ? Optional.empty() : scopeMethodKey;
        rationale = Objects.requireNonNull(rationale, "rationale").strip();
        if (rationale.isEmpty() || rationale.length() > 4_096) {
            throw new IllegalArgumentException("rationale must contain between 1 and 4096 characters");
        }
        intent = Objects.requireNonNull(intent, "intent");
        budget = Objects.requireNonNull(budget, "budget");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
