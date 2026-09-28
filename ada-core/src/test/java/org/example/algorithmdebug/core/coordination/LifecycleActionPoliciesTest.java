package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.junit.jupiter.api.Test;

class LifecycleActionPoliciesTest {

    @Test
    void analysisBeginAllowsOnlyTheAbsentBootstrapState() {
        PolicyTestFixtures.Prerequisites prerequisites = new PolicyTestFixtures.Prerequisites();
        prerequisites.initialization = CoreActionPrerequisites.AnalysisInitialization.ABSENT;
        var policy = LifecycleActionPolicies.analysisBegin(prerequisites);
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("problem-1"),
                PolicyTestFixtures.IDENTITY.caseId(), PolicyTestFixtures.IDENTITY.analysisId(),
                "结果顺序错误", "测试通过", "断言失败",
                new TargetTest("a.b.ScheduleTest", "runs"),
                List.of(new SourceAnchor(
                        "a.b.Scheduler", "schedule", "()V",
                        "src/main/java/a/b/Scheduler.java", 1, 10)),
                List.of(), List.of("候选选择是否错误"), Instant.EPOCH);
        var request = PolicyTestFixtures.request(
                AnalysisActionType.ANALYSIS_BEGIN,
                new CoreActionInputs.AnalysisBegin(frame, Optional.empty(), Optional.empty()),
                true, Optional.empty());

        assertEquals(ActionDecisionCode.ALLOWED,
                policy.authorize(PolicyTestFixtures.view(), request).decision());

        prerequisites.initialization = CoreActionPrerequisites.AnalysisInitialization.INVALID;
        assertEquals(ActionDecisionCode.REJECTED,
                policy.authorize(PolicyTestFixtures.view(), request).decision());
        prerequisites.initialization = CoreActionPrerequisites.AnalysisInitialization.INITIALIZED;
        assertEquals(ActionDecisionCode.REJECTED,
                policy.authorize(PolicyTestFixtures.view(), request).decision());
    }
}
