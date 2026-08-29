package org.example.algorithmdebug.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.PlanId;

/** 大模型提交给确定性 JDWP 计划编译器的有界意图。 */
public record JdwpPlanRequest(
        PlanId planId,
        List<JdwpTracepointRequest> tracepoints,
        JdwpCollectionBudget budget,
        String rationale,
        Instant requestedAt) {

    /** 防御性复制请求；语义和源码一致性由编译器验证。 */
    public JdwpPlanRequest {
        planId = Objects.requireNonNull(planId, "planId");
        tracepoints = List.copyOf(Objects.requireNonNull(tracepoints, "tracepoints"));
        if (tracepoints.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("tracepoints must not contain null");
        }
        budget = Objects.requireNonNull(budget, "budget");
        rationale = Objects.requireNonNull(rationale, "rationale");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
