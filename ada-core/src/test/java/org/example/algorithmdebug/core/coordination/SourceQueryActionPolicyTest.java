package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryMode;
import org.junit.jupiter.api.Test;

class SourceQueryActionPolicyTest {

    @Test
    void sourceQueryRequiresTheCurrentAnalysisMethodCatalog() {
        PolicyTestFixtures.Prerequisites prerequisites = new PolicyTestFixtures.Prerequisites();
        var policy = AnalysisActionPolicies.sourceQuery(prerequisites);
        var input = new CoreActionInputs.SourceQuery(
                SourceQueryMode.SEARCH_SYMBOL,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("Scheduler"), SourceQueryBudget.defaults());
        prerequisites.methodCatalog = false;

        var rejected = policy.authorize(
                PolicyTestFixtures.view(), PolicyTestFixtures.request(
                        AnalysisActionType.SOURCE_QUERY, input, true, Optional.empty()));
        prerequisites.methodCatalog = true;
        var allowed = policy.authorize(
                PolicyTestFixtures.view(), PolicyTestFixtures.request(
                        AnalysisActionType.SOURCE_QUERY, input, true, Optional.empty()));

        assertEquals(ActionDecisionCode.REJECTED, rejected.decision());
        assertEquals(ActionDecisionCode.ALLOWED, allowed.decision());
    }
}
