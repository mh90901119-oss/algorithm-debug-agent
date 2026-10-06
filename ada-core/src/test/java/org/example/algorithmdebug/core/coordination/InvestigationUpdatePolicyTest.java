package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationUpdateCommand;
import org.junit.jupiter.api.Test;

class InvestigationUpdatePolicyTest {

    @Test
    void acceptsOnlyTypedModelCommandsForTheCurrentAnalysis() {
        PolicyTestFixtures.Prerequisites prerequisites = new PolicyTestFixtures.Prerequisites();
        InvestigationUpdatePolicy policy = new InvestigationUpdatePolicy(prerequisites);
        var command = new InvestigationUpdateCommand.AddHypothesis(
                PolicyTestFixtures.IDENTITY.caseId(), PolicyTestFixtures.IDENTITY.analysisId(),
                new HypothesisRecord(
                        SchemaVersions.HYPOTHESIS_RECORD, new HypothesisId("hypothesis-1"),
                        PolicyTestFixtures.IDENTITY.caseId(),
                        PolicyTestFixtures.IDENTITY.analysisId(),
                        "候选选择条件错误", HypothesisStatus.OPEN,
                        List.of(new SourceAnchor(
                                "a.b.Scheduler", "select", "()V",
                                "src/main/java/a/b/Scheduler.java", 1, 10)),
                        List.of(), List.of(), List.of(), Instant.EPOCH));
        var input = new CoreActionInputs.InvestigationUpdate(command);

        var decision = policy.authorize(
                PolicyTestFixtures.view(),
                PolicyTestFixtures.request(
                        AnalysisActionType.INVESTIGATION_UPDATE,
                        input, true, Optional.empty()));

        assertEquals(ActionDecisionCode.ALLOWED, decision.decision());
        assertTrue(InvestigationUpdateCommand.class.isAssignableFrom(
                CoreActionInputs.InvestigationUpdate.class.getRecordComponents()[0].getType()));
    }

    @Test
    void rejectsCommandForAnotherAnalysisWithoutCallingAHandler() {
        PolicyTestFixtures.Prerequisites prerequisites = new PolicyTestFixtures.Prerequisites();
        InvestigationUpdatePolicy policy = new InvestigationUpdatePolicy(prerequisites);
        var foreign = new InvestigationUpdateCommand.MarkGapUnresolved(
                new CaseId("case-2"), new AnalysisId("analysis-2"),
                new org.example.algorithmdebug.contracts.investigation.EvidenceGapId("gap-1"),
                "仍缺少运行时状态");

        var decision = policy.authorize(
                PolicyTestFixtures.view(),
                PolicyTestFixtures.request(
                        AnalysisActionType.INVESTIGATION_UPDATE,
                        new CoreActionInputs.InvestigationUpdate(foreign),
                        true, Optional.empty()));

        assertEquals(ActionDecisionCode.REJECTED, decision.decision());
    }
}
