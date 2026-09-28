package org.example.algorithmdebug.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.ToolResponse;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.ActionTarget;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.core.coordination.CoreActionInputs;
import org.junit.jupiter.api.Test;

class CliCoordinatorCompatibilityTest {
    private static final ProjectId PROJECT = new ProjectId("project-1");
    private static final CaseId CASE = new CaseId("case-1");
    private static final AnalysisId ANALYSIS = new AnalysisId("analysis-1");
    private static final AnalysisIdentity IDENTITY = new AnalysisIdentity(PROJECT, CASE, ANALYSIS);
    private static final Path WORKSPACE = Path.of("workspace");

    @Test
    void modelCommandsInvokeCoordinatorExactlyOnce() {
        AtomicInteger managementCalls = new AtomicInteger();
        AtomicInteger mappings = new AtomicInteger();
        AtomicInteger coordinatorCalls = new AtomicInteger();
        AnalysisActionRequest<?> request = request();
        CliCommandExecutor executor = new CliCommandExecutor(
                command -> {
                    managementCalls.incrementAndGet();
                    return Map.of("unexpected", true);
                },
                command -> {
                    mappings.incrementAndGet();
                    return request;
                },
                action -> {
                    coordinatorCalls.incrementAndGet();
                    return succeeded(action.actionType(), Map.of("command", "executed"));
                },
                new CliCoordinatedResultAdapter());

        for (CliCommand command : modelCommands()) {
            ToolResponse<?> response = executor.execute(command);
            assertTrue(response.success(), command.getClass().getSimpleName());
        }

        assertEquals(modelCommands().size(), mappings.get());
        assertEquals(modelCommands().size(), coordinatorCalls.get());
        assertEquals(0, managementCalls.get());
    }

    @Test
    void managementCommandsRemainDirectAndDoNotPretendToBeAnalysisActions() {
        AtomicInteger managementCalls = new AtomicInteger();
        AtomicInteger mappings = new AtomicInteger();
        AtomicInteger coordinatorCalls = new AtomicInteger();
        CliCommandExecutor executor = new CliCommandExecutor(
                command -> {
                    managementCalls.incrementAndGet();
                    return Map.of("management", command.getClass().getSimpleName());
                },
                command -> {
                    mappings.incrementAndGet();
                    return request();
                },
                action -> {
                    coordinatorCalls.incrementAndGet();
                    return succeeded(action.actionType(), Map.of("unexpected", true));
                },
                new CliCoordinatedResultAdapter());

        List<CliCommand> commands = List.of(
                new CliCommand.WorkspaceInit(WORKSPACE),
                new CliCommand.ProjectRegister(
                        WORKSPACE, Path.of("module"), Optional.empty(), Optional.empty()),
                new CliCommand.Doctor(WORKSPACE, Optional.empty()));
        commands.forEach(command -> assertTrue(executor.execute(command).success()));

        assertEquals(3, managementCalls.get());
        assertEquals(0, mappings.get());
        assertEquals(0, coordinatorCalls.get());
    }

    @Test
    void rejectedDecisionMapsToStableCliErrorWithoutLosingReasonCode() {
        CoordinationErrorCode reason = CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED;
        CoordinatedToolResult<?> coordinated = new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.REJECTED,
                reason.name(),
                "internal policy message",
                reason,
                List.of(),
                control(
                        AnalysisActionType.RUN_TEST,
                        ActionDecisionCode.REJECTED,
                        List.of(reason)));

        ToolResponse<?> response = new CliCoordinatedResultAdapter().adapt(coordinated);

        assertFalse(response.success());
        assertEquals(reason.name(), response.code());
        assertTrue(response.message().contains("not target-test evidence"));
    }

    @Test
    void targetFailureRemainsSuccessfulCliInvocationWithTargetOutcome() {
        Map<String, String> failedTarget = Map.of("testOutcome", "FAILED");
        ToolResponse<?> response = new CliCoordinatedResultAdapter().adapt(
                succeeded(AnalysisActionType.RUN_TEST, failedTarget));

        assertTrue(response.success());
        assertEquals("OK", response.code());
        assertEquals(failedTarget, response.data());
    }

    @Test
    void toolResponseV2GoldenRemainsReadable() throws Exception {
        ToolResponse<?> response = new CliCoordinatedResultAdapter().adapt(
                succeeded(AnalysisActionType.CASE_INSPECT, Map.of("caseId", "case-1")));

        String json = new ObjectMapper().writeValueAsString(response);
        ToolResponse<?> decoded = new ObjectMapper().readValue(json, ToolResponse.class);

        assertEquals(SchemaVersions.TOOL_RESPONSE, decoded.schemaVersion());
        assertTrue(decoded.success());
        assertEquals("OK", decoded.code());
    }

    private static List<CliCommand> modelCommands() {
        Path input = Path.of("input.json");
        return new ArrayList<>(List.of(
                new CliCommand.CaseOpen(
                        WORKSPACE, PROJECT, new TargetTest("a.b.Test", "case1"), input,
                        Optional.empty(), Optional.empty()),
                new CliCommand.CaseInspect(WORKSPACE, PROJECT, CASE),
                new CliCommand.AlgorithmInputCapture(WORKSPACE, PROJECT, CASE, ANALYSIS),
                new CliCommand.CaseAudit(WORKSPACE, PROJECT, CASE),
                new CliCommand.GanttInspect(
                        WORKSPACE, PROJECT, CASE, "gantt-1", "summary", "", 0, 10),
                new CliCommand.RunExecute(WORKSPACE, PROJECT, CASE, ANALYSIS),
                new CliCommand.StaticAnalyze(WORKSPACE, PROJECT, CASE, ANALYSIS),
                new CliCommand.CodePathPlanCreate(WORKSPACE, PROJECT, CASE, ANALYSIS, input),
                new CliCommand.CodePathCollectionExecute(
                        WORKSPACE, PROJECT, CASE, new PlanId("plan-1")),
                new CliCommand.JdwpPlanCreate(WORKSPACE, PROJECT, CASE, ANALYSIS, input),
                new CliCommand.JdwpCollectionExecute(
                        WORKSPACE, PROJECT, CASE, new PlanId("jdwp-plan-1")),
                new CliCommand.ArtifactRead(
                        WORKSPACE, PROJECT, CASE, "artifact-1", 0, 1024),
                new CliCommand.EvidenceQuery(
                        WORKSPACE, PROJECT, CASE, "evidence-1", input)));
    }

    private static AnalysisActionRequest<?> request() {
        return new AnalysisActionRequest.Command<>(
                SchemaVersions.ANALYSIS_ACTION_REQUEST,
                AnalysisActionType.CASE_INSPECT,
                IDENTITY,
                new ActionTarget(
                        "workspace-1", PROJECT, CASE, ANALYSIS,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                Optional.empty(),
                CoreActionInputs.NoInput.INSTANCE);
    }

    private static CoordinatedToolResult<?> succeeded(
            AnalysisActionType actionType, Object data) {
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT,
                ActionOutcome.SUCCEEDED,
                "ACTION_COMPLETED",
                "Action completed",
                data,
                List.of(),
                control(actionType, ActionDecisionCode.ALLOWED, List.of()));
    }

    private static AnalysisControlView control(
            AnalysisActionType actionType,
            ActionDecisionCode decision,
            List<CoordinationErrorCode> reasons) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                IDENTITY,
                1,
                actionType,
                decision,
                reasons,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(AnalysisActionType.CASE_INSPECT),
                ConclusionStatus.MISSING_EVIDENCE);
    }
}
