package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Root;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.ActionOutcome;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.core.coordination.CoreActionInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolDispatcherTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-dispatch");
    private static final CaseId CASE_ID = new CaseId("case-dispatch");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-dispatch");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    private Path workspace;
    private Path project;
    private McpServerLifecycle lifecycle;
    private McpRequestContextResolver contexts;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(directory.resolve("workspace"));
        project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        new ProjectRegistrationRepository(new BoundedDocumentMapper(),
                new AtomicDocumentWriter()).create(
                WorkspaceLayout.of(workspace), new ProjectRegistration(
                        SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "Dispatch Project",
                        project.toString(), project.toString(), project.toString(), "pom.xml",
                        "MAVEN", CLOCK.instant()));
        lifecycle = new McpServerLifecycle(
                McpServerLimits.DEFAULT_SHUTDOWN_TIMEOUT, 2, () -> { }, () -> { });
        lifecycle.start();
        contexts = new McpRequestContextResolver(workspace, project, "workspace-dispatch");
    }

    @Test
    void knownToolBindsTypedInputAndCallsCoordinatorExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AnalysisActionRequest<?>> captured = new AtomicReference<>();
        McpToolDispatcher dispatcher = dispatcher((request, cancellation) -> {
            calls.incrementAndGet();
            captured.set(request);
            return succeeded(request.identity(), request.actionType());
        });
        McpRequestId requestId = McpRequestId.fromWire("status-request");
        lifecycle.observeCall(requestId);

        var result = dispatcher.dispatch(roots(), new CallToolRequest(
                "analysis_status",
                Map.of("caseId", CASE_ID.value(), "analysisId", ANALYSIS_ID.value()),
                Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY, requestId.wireValue())));

        assertFalse(result.isError());
        assertEquals(1, calls.get());
        assertEquals(AnalysisActionType.ANALYSIS_STATUS, captured.get().actionType());
        assertEquals(CoreActionInputs.NoInput.INSTANCE, captured.get().payload());
        assertEquals(0, lifecycle.activeCallCount());
    }

    @Test
    void invalidSchemaAndInternalFailureBecomeSanitizedProtocolErrors() {
        McpToolDispatcher dispatcher = dispatcher((request, cancellation) -> {
            throw new IllegalStateException("D:\\secret\\internal");
        });
        McpRequestId invalidId = McpRequestId.fromWire("invalid");
        lifecycle.observeCall(invalidId);
        McpError invalid = assertThrows(McpError.class, () -> dispatcher.dispatch(
                roots(), new CallToolRequest(
                        "analysis_status",
                        Map.of("caseId", CASE_ID.value(), "analysisId", ANALYSIS_ID.value(),
                                "unexpected", true),
                        Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY,
                                invalidId.wireValue()))));
        assertEquals(-32602, invalid.getJsonRpcError().code());
        assertEquals(0, lifecycle.activeCallCount());

        McpRequestId unknownId = McpRequestId.fromWire("unknown");
        lifecycle.observeCall(unknownId);
        McpError unknown = assertThrows(McpError.class, () -> dispatcher.dispatch(
                roots(), new CallToolRequest(
                        "unknown_tool", Map.of(),
                        Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY,
                                unknownId.wireValue()))));
        assertEquals(-32602, unknown.getJsonRpcError().code());
        assertEquals(0, lifecycle.activeCallCount());

        McpRequestId failureId = McpRequestId.fromWire("failure");
        lifecycle.observeCall(failureId);
        McpError internal = assertThrows(McpError.class, () -> dispatcher.dispatch(
                roots(), new CallToolRequest(
                        "analysis_status",
                        Map.of("caseId", CASE_ID.value(), "analysisId", ANALYSIS_ID.value()),
                        Map.of(McpProtocolInputStream.REQUEST_ID_META_KEY,
                                failureId.wireValue()))));
        assertEquals(-32603, internal.getJsonRpcError().code());
        assertFalse(internal.toString().contains("secret"));
        assertEquals(0, lifecycle.activeCallCount());
    }

    private McpToolDispatcher dispatcher(McpToolDispatcher.CoordinatorExecutor executor) {
        return new McpToolDispatcher(
                McpToolCatalog.load(), contexts, lifecycle, executor,
                new McpResultMapper(), CLOCK);
    }

    private List<Root> roots() {
        return List.of(new Root(project.toUri().toString(), "target"));
    }

    private static CoordinatedToolResult<?> succeeded(
            AnalysisIdentity identity, AnalysisActionType type) {
        AnalysisControlView control = new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW, "policy-v1", identity, 1,
                type, ActionDecisionCode.ALLOWED, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(type),
                ConclusionStatus.MISSING_EVIDENCE);
        return new CoordinatedToolResult<>(
                SchemaVersions.COORDINATED_TOOL_RESULT, ActionOutcome.SUCCEEDED,
                "ACTION_SUCCEEDED", "completed", control, List.of(), control);
    }
}
