package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.junit.jupiter.api.Test;

class JdwpCollectActionPolicyTest {

    @Test
    void doesNotAcceptACodePathPlanAsAJdwpPlan() {
        PolicyTestFixtures.Prerequisites prerequisites = new PolicyTestFixtures.Prerequisites();
        var policy = TargetExecutionPolicies.jdwpCollect(prerequisites);
        PlanId planId = new PlanId("plan-1");
        prerequisites.codePathPlans.add(planId.value());
        var request = PolicyTestFixtures.request(
                AnalysisActionType.JDWP_COLLECT,
                new CoreActionInputs.JdwpCollect(planId), true, Optional.of(planId));

        assertEquals(ActionDecisionCode.REJECTED,
                policy.authorize(PolicyTestFixtures.view(), request).decision());

        prerequisites.jdwpPlans.add(planId.value());
        assertEquals(ActionDecisionCode.ALLOWED,
                policy.authorize(PolicyTestFixtures.view(), request).decision());
    }
}
