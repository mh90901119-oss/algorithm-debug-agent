package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.junit.jupiter.api.Test;

class RunTestActionPolicyTest {

    @Test
    void requiresCapturedInputAndOperationIdBeforeRunningTargetUt() {
        PolicyTestFixtures.Prerequisites prerequisites = new PolicyTestFixtures.Prerequisites();
        var policy = TargetExecutionPolicies.runTest(prerequisites);
        prerequisites.inputCaptured = false;

        var missingInput = policy.authorize(
                PolicyTestFixtures.view(), PolicyTestFixtures.request(
                        AnalysisActionType.RUN_TEST, CoreActionInputs.NoInput.INSTANCE,
                        true, Optional.empty()));
        prerequisites.inputCaptured = true;
        var missingOperation = policy.authorize(
                PolicyTestFixtures.view(), PolicyTestFixtures.request(
                        AnalysisActionType.RUN_TEST, CoreActionInputs.NoInput.INSTANCE,
                        false, Optional.empty()));
        var allowed = policy.authorize(
                PolicyTestFixtures.view(), PolicyTestFixtures.request(
                        AnalysisActionType.RUN_TEST, CoreActionInputs.NoInput.INSTANCE,
                        true, Optional.empty()));

        assertEquals(ActionDecisionCode.REJECTED, missingInput.decision());
        assertEquals(ActionDecisionCode.REJECTED, missingOperation.decision());
        assertEquals(ActionDecisionCode.ALLOWED, allowed.decision());

        prerequisites.actionAvailable = false;
        assertEquals(ActionDecisionCode.REJECTED,
                policy.authorize(
                        PolicyTestFixtures.view(), PolicyTestFixtures.request(
                                AnalysisActionType.RUN_TEST, CoreActionInputs.NoInput.INSTANCE,
                                true, Optional.empty())).decision());
    }
}
