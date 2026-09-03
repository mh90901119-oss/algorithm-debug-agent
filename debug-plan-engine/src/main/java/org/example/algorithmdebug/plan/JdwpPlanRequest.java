package org.example.algorithmdebug.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.example.algorithmdebug.contracts.InvestigationIntent;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.PlanId;

/** 大模型提交给确定性 JDWP 编译器的有界意图。 */
public record JdwpPlanRequest(
        PlanId planId,
        List<JdwpTracepointRequest> tracepoints,
        JdwpCollectionBudget budget,
        String rationale,
        InvestigationIntent intent,
        Instant requestedAt) {

    public JdwpPlanRequest {
        planId = Objects.requireNonNull(planId, "planId");
        tracepoints = List.copyOf(Objects.requireNonNull(tracepoints, "tracepoints"));
        if (tracepoints.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("tracepoints must not contain null");
        }
        budget = Objects.requireNonNull(budget, "budget");
        rationale = Objects.requireNonNull(rationale, "rationale").strip();
        if (rationale.isEmpty() || rationale.length() > 4_096) {
            throw new IllegalArgumentException("rationale must contain between 1 and 4096 characters");
        }
        intent = Objects.requireNonNull(intent, "intent");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
