package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.junit.jupiter.api.Test;

class CodePathCollectActionPolicyTest {

    @Test
    void requiresAnExactCurrentCodePathPlanAndOperationId() {
        PolicyTestFixtures.Prerequisites prerequisites = new PolicyTestFixtures.Prerequisites();
        var policy = TargetExecutionPolicies.codePathCollect(prerequisites);
        PlanId planId = new PlanId("plan-1");
        var request = PolicyTestFixtures.request(
                AnalysisActionType.CODEPATH_COLLECT,
                new CoreActionInputs.CodePathCollect(planId), true, Optional.of(planId));

        assertEquals(ActionDecisionCode.REJECTED,
                policy.authorize(PolicyTestFixtures.view(), request).decision());

        prerequisites.codePathPlans.add(planId.value());
        assertEquals(ActionDecisionCode.ALLOWED,
                policy.authorize(PolicyTestFixtures.view(), request).decision());

        var wrongTarget = PolicyTestFixtures.request(
                AnalysisActionType.CODEPATH_COLLECT,
                new CoreActionInputs.CodePathCollect(planId), true,
                Optional.of(new PlanId("plan-2")));
        assertEquals(ActionDecisionCode.REJECTED,
                policy.authorize(PolicyTestFixtures.view(), wrongTarget).decision());
    }
}
