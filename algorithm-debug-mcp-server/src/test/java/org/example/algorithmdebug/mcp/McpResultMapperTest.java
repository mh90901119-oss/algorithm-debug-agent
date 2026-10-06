package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.junit.jupiter.api.Test;

class McpResultMapperTest {

    @Test
    void targetUtFailureAndCoordinatorRejectionRemainStructuredToolResults() {
        McpResultMapper mapper = new McpResultMapper();

        CallToolResult failed = mapper.map(result(
                ActionOutcome.FAILED, "TARGET_TEST_FAILED", Map.of("exitCode", 1)));
        CallToolResult rejected = mapper.map(result(
                ActionOutcome.REJECTED, "COORDINATION_ACTION_NOT_ALLOWED", Map.of()));

        assertFalse(failed.isError());
        assertFalse(rejected.isError());
        assertEquals("FAILED", field(failed, "outcome"));
        assertEquals("REJECTED", field(rejected, "outcome"));
        assertTrue(((TextContent) failed.content().getFirst()).text()
                .contains("TARGET_TEST_FAILED"));
    }

    @Test
    void oversizedResultReturnsBoundedSummaryWithoutLosingControl() throws Exception {
        McpResultMapper mapper = new McpResultMapper();
        CoordinatedToolResult<?> result = result(
                ActionOutcome.SUCCEEDED, "ACTION_SUCCEEDED",
                Map.of("oversized", "x".repeat(McpServerLimits.MAX_RESULT_BYTES)));

        CallToolResult mapped = mapper.map(result);
        byte[] bytes = new ObjectMapper().writeValueAsBytes(mapped.structuredContent());

        assertTrue(bytes.length <= McpServerLimits.MAX_RESULT_BYTES);
        assertEquals(true, ((Map<?, ?>) ((Map<?, ?>) mapped.structuredContent())
                .get("data")).get("truncated"));
        assertTrue(((Map<?, ?>) mapped.structuredContent()).containsKey("control"));
    }

    @Test
    void requestBudgetSizedDataRemainsCompleteForFinalizationRoundTrip() {
        McpResultMapper mapper = new McpResultMapper();
        CoordinatedToolResult<?> result = result(
                ActionOutcome.SUCCEEDED, "ACTION_SUCCEEDED",
                Map.of("candidate", "x".repeat(McpServerLimits.MAX_REQUEST_BYTES)));

        CallToolResult mapped = mapper.map(result);

        assertEquals(McpServerLimits.MAX_REQUEST_BYTES
                        + McpServerLimits.MAX_COORDINATION_RESPONSE_OVERHEAD_BYTES,
                McpServerLimits.MAX_RESULT_BYTES);
        assertEquals(McpServerLimits.MAX_REQUEST_BYTES,
                ((String) ((Map<?, ?>) ((Map<?, ?>) mapped.structuredContent())
                        .get("data")).get("candidate")).length());
    }

    private static Object field(CallToolResult result, String name) {
        return ((Map<?, ?>) result.structuredContent()).get(name);
    }

    private static CoordinatedToolResult<?> result(
            ActionOutcome outcome, String code, Object data) {
        AnalysisIdentity identity = new AnalysisIdentity(
                new ProjectId("project-result"), new CaseId("case-result"),
                new AnalysisId("analysis-result"));
        ActionDecisionCode decision = outcome == ActionOutcome.REJECTED
                ? ActionDecisionCode.REJECTED : ActionDecisionCode.ALLOWED;
        var reasons = outcome == ActionOutcome.REJECTED
                ? List.of(org.example.algorithmdebug.contracts.coordination
                .CoordinationErrorCode.COORDINATION_ACTION_NOT_ALLOWED)
                : List.<org.example.algorithmdebug.contracts.coordination
                .CoordinationErrorCode>of();
        AnalysisControlView control = new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW, "policy-v1", identity, 1,
                AnalysisActionType.RUN_TEST, decision, reasons,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), ConclusionStatus.MISSING_EVIDENCE);
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT, outcome, code,
                "bounded result", data, List.of(), control);
    }
}
