package org.example.algorithmdebug.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.PlanId;

/** 大模型选择关键方法后交给确定性编译器的有界请求。 */
public record CodePathPlanRequest(
        PlanId planId,
        List<String> selectedMethodKeys,
        Optional<String> scopeMethodKey,
        String rationale,
        CollectionBudget budget,
        Instant requestedAt) {

    /** 做基本空值检查；目录成员关系由编译器验证。 */
    public CodePathPlanRequest {
        planId = Objects.requireNonNull(planId, "planId");
        selectedMethodKeys = List.copyOf(Objects.requireNonNull(selectedMethodKeys, "selectedMethodKeys"));
        scopeMethodKey = scopeMethodKey == null ? Optional.empty() : scopeMethodKey;
        rationale = Objects.requireNonNull(rationale, "rationale");
        budget = Objects.requireNonNull(budget, "budget");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /** 兼容未配置 Scope 的旧 Tool 和测试调用。 */
    public CodePathPlanRequest(
            PlanId planId,
            List<String> selectedMethodKeys,
            String rationale,
            CollectionBudget budget,
            Instant requestedAt) {
        this(planId, selectedMethodKeys, Optional.empty(), rationale, budget, requestedAt);
    }
}
