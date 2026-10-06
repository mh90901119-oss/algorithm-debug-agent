package org.example.algorithmdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.example.algorithmdebug.core.coordination.ActionCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioMcpContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"contract-test","version":"1.0"}}}
            """;

    @TempDir
    Path directory;

    @Test
    void initializeNegotiatesStableServerIdentityAndProtocolOnlyStdout() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            harness.send(INITIALIZE);

            JsonNode response = JSON.readTree(harness.awaitLine());

            assertEquals("2.0", response.path("jsonrpc").asText());
            assertEquals(1, response.path("id").asInt());
            assertEquals("2025-06-18", response.path("result").path("protocolVersion").asText());
            assertEquals(AlgorithmDebugMcpServer.SERVER_NAME,
                    response.path("result").path("serverInfo").path("name").asText());
            assertEquals(AlgorithmDebugMcpServer.SERVER_VERSION,
                    response.path("result").path("serverInfo").path("version").asText());
            assertTrue(response.path("result").path("capabilities").isObject());
            assertFalse(harness.hasAdditionalLine());
        }
    }

    @Test
    void everyJsonRpcRequestUsesTheGlobalActiveCallBudget() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            for (int index = 0; index < McpServerLimits.DEFAULT_ACTIVE_CALLS; index++) {
                harness.server.lifecycle().observeCall(
                        McpRequestId.fromWire("occupied-" + index));
            }

            harness.send(INITIALIZE);
            JsonNode rejected = JSON.readTree(harness.awaitLine());
            assertEquals(1, rejected.path("id").asInt());
            assertEquals(-32001, rejected.path("error").path("code").asInt());

            for (int index = 0; index < McpServerLimits.DEFAULT_ACTIVE_CALLS; index++) {
                harness.server.lifecycle().completeUnclaimedCall(
                        McpRequestId.fromWire("occupied-" + index));
            }
            harness.send(INITIALIZE);
            assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
            assertEquals(0, harness.server.lifecycle().activeCallCount());
        }
    }

    @Test
    void malformedJsonRpcAndUnknownCapabilityAreProtocolErrorsWithoutPoisoningSession()
            throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            harness.send("{not-json}");
            JsonNode malformed = JSON.readTree(harness.awaitLine());
            assertEquals(-32700, malformed.path("error").path("code").asInt());
            assertTrue(malformed.path("id").isNull());

            harness.sendRaw(new byte[] {(byte) 0xC3, 0x28, '\n'});
            JsonNode invalidUtf8 = JSON.readTree(harness.awaitLine());
            assertEquals(-32700, invalidUtf8.path("error").path("code").asInt());

            harness.send("""
                    {"jsonrpc":"2.0","id":3}
                    """);
            JsonNode invalidRequest = JSON.readTree(harness.awaitLine());
            assertEquals(-32600, invalidRequest.path("error").path("code").asInt());

            harness.send(INITIALIZE);
            assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
            harness.send("""
                    {"jsonrpc":"2.0","id":2,"method":"algorithm-debug/unknown","params":{}}
                    """);
            JsonNode unknown = JSON.readTree(harness.awaitLine());
            assertEquals(2, unknown.path("id").asInt());
            assertEquals(-32601, unknown.path("error").path("code").asInt());
        }
    }

    @Test
    void concatenatedOrTrailingJsonNeverExecutesTheFirstValue() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            harness.sendRaw((INITIALIZE.strip() + " {}\n")
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals(-32700,
                    JSON.readTree(harness.awaitLine()).path("error").path("code").asInt());

            harness.sendRaw((INITIALIZE.strip() + " trailing\n")
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals(-32700,
                    JSON.readTree(harness.awaitLine()).path("error").path("code").asInt());

            harness.send(INITIALIZE);
            assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
        }
    }

    @Test
    void wrongJsonRpcVersionCannotCancelAnActiveCall() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            McpRequestId requestId = McpRequestId.fromWire(17);
            ActionCancellation cancellation = ActionCancellation.active();
            try (McpServerLifecycle.CallLease ignored =
                         harness.server.lifecycle().beginCall(requestId, cancellation)) {
                harness.send("""
                        {"jsonrpc":"1.0","method":"notifications/cancelled","params":{"requestId":17}}
                        """);
                harness.send(INITIALIZE);
                assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
                assertFalse(cancellation.isCancellationRequested());

                harness.send("""
                        {"jsonrpc":"1.0","id":18,"method":"ping","params":{}}
                        """);
                assertEquals(-32600,
                        JSON.readTree(harness.awaitLine()).path("error").path("code").asInt());
                assertFalse(cancellation.isCancellationRequested());
            }
        }
    }

    @Test
    void wireCancellationReachesTheMatchingCoordinatorToken() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            McpRequestId requestId = McpRequestId.fromWire(17);
            harness.server.lifecycle().observeCall(requestId);
            ActionCancellation cancellation = ActionCancellation.active();
            CountDownLatch callback = new CountDownLatch(1);
            try (McpServerLifecycle.CallLease ignored =
                         harness.server.lifecycle().beginCall(requestId, cancellation)) {
                harness.send("""
                        {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":17,"reason":"client stopped"}}
                        """);
                harness.send(INITIALIZE);
                assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
                assertTrue(cancellation.isCancellationRequested());
                cancellation.onCancellation(timeout -> callback.countDown());
                assertTrue(callback.await(1, TimeUnit.SECONDS));
                assertFalse(harness.hasAdditionalLine());
            }
        }
    }

    @Test
    void sdkResponseReleasesAnUnclaimedToolCallPlaceholder() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            harness.send(INITIALIZE);
            harness.awaitLine();
            harness.send("""
                    {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                    """);
            harness.send("""
                    {"jsonrpc":"2.0","id":"unknown-call","method":"tools/call","params":{"name":"missing","arguments":{}}}
                    """);

            JsonNode response = JSON.readTree(harness.awaitLine());
            assertEquals("unknown-call", response.path("id").asText());
            assertEquals(-32601, response.path("error").path("code").asInt());
            assertEquals(0, harness.server.lifecycle().activeCallCount());
        }
    }

    @Test
    void missingToolCallParamsAreInvalidParamsWithoutPoisoningTheSession()
            throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            harness.send(INITIALIZE);
            harness.awaitLine();
            harness.send("""
                    {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                    """);
            harness.send("""
                    {"jsonrpc":"2.0","id":"missing-params","method":"tools/call"}
                    """);

            JsonNode invalid = JSON.readTree(harness.awaitLine());
            assertEquals("missing-params", invalid.path("id").asText());
            assertEquals(-32602, invalid.path("error").path("code").asInt());
            assertEquals(0, harness.server.lifecycle().activeCallCount());

            harness.send("""
                    {"jsonrpc":"2.0","id":"unknown-after-invalid","method":"tools/call","params":{"name":"missing","arguments":{}}}
                    """);
            JsonNode continued = JSON.readTree(harness.awaitLine());
            assertEquals("unknown-after-invalid", continued.path("id").asText());
            assertEquals(-32601, continued.path("error").path("code").asInt());
        }
    }

    @Test
    void rejectedDuplicateWireIdDoesNotReleaseTheOriginalActiveCall() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            McpRequestId requestId = McpRequestId.fromWire("same-id");
            ActionCancellation original = ActionCancellation.active();
            CountDownLatch cancelled = new CountDownLatch(1);
            original.onCancellation(timeout -> cancelled.countDown());
            try (McpServerLifecycle.CallLease ignored =
                         harness.server.lifecycle().beginCall(requestId, original)) {
                harness.send("""
                        {"jsonrpc":"2.0","id":"same-id","method":"tools/call","params":{"name":"duplicate","arguments":{}}}
                        """);

                JsonNode response = JSON.readTree(harness.awaitLine());
                assertEquals(-32001, response.path("error").path("code").asInt());
                assertEquals(1, harness.server.lifecycle().activeCallCount());
                harness.send("""
                        {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"same-id"}}
                        """);
                assertTrue(cancelled.await(1, TimeUnit.SECONDS));
                assertTrue(original.isCancellationRequested());
            }
        }
    }

    @Test
    void oversizedFrameIsRejectedWithoutAllocatingAnUnboundedRequest() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            harness.send("x".repeat(McpServerLimits.MAX_REQUEST_BYTES + 1));

            JsonNode response = JSON.readTree(harness.awaitLine());

            assertEquals(-32600, response.path("error").path("code").asInt());
            assertEquals("MCP_REQUEST_TOO_LARGE",
                    response.path("error").path("data").path("code").asText());
            harness.send(INITIALIZE);
            assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
        }
    }

    @Test
    void metadataExpansionPastFrameLimitIsRejectedWithoutClosingTheSession()
            throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            String prefix = "{\"jsonrpc\":\"2.0\",\"id\":\"near-limit\","
                    + "\"method\":\"tools/call\",\"params\":{\"name\":\"missing\","
                    + "\"arguments\":{\"payload\":\"";
            String suffix = "\"}}}";
            int payloadBytes = McpServerLimits.MAX_REQUEST_BYTES
                    - prefix.getBytes(StandardCharsets.UTF_8).length
                    - suffix.getBytes(StandardCharsets.UTF_8).length;
            String request = prefix + "x".repeat(payloadBytes) + suffix;
            assertEquals(McpServerLimits.MAX_REQUEST_BYTES,
                    request.getBytes(StandardCharsets.UTF_8).length);

            harness.send(request);
            JsonNode response = JSON.readTree(harness.awaitLine());
            assertEquals("near-limit", response.path("id").asText());
            assertEquals(-32600, response.path("error").path("code").asInt());
            assertEquals("MCP_REQUEST_TOO_LARGE",
                    response.path("error").path("data").path("code").asText());

            harness.send(INITIALIZE);
            assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
        }
    }

    @Test
    void overlongRequestIdIsRejectedWithoutPoisoningTheSession() throws Exception {
        try (ProtocolHarness harness = openHarness()) {
            String requestId = "r".repeat(McpServerLimits.MAX_REQUEST_ID_LENGTH + 1);
            harness.send("""
                    {"jsonrpc":"2.0","id":"%s","method":"tools/call","params":{"name":"missing","arguments":{}}}
                    """.formatted(requestId));

            JsonNode invalid = JSON.readTree(harness.awaitLine());
            assertEquals(-32600, invalid.path("error").path("code").asInt());
            assertEquals(requestId, invalid.path("id").asText());
            harness.send(INITIALIZE);
            assertEquals(1, JSON.readTree(harness.awaitLine()).path("id").asInt());
        }
    }

    @Test
    void startupDiagnosticsStayOnStderrAndNeverContaminateStdout() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = AlgorithmDebugMcpMain.run(
                new String[] {"--unknown"},
                new ByteArrayInputStream(new byte[0]), stdout,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertEquals(AlgorithmDebugMcpMain.EXIT_INVALID_ARGUMENTS, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("MCP_SERVER_INVALID_ARGUMENTS"));
    }

    @Test
    void shutdownHookRegistrationFailureClosesStartedServer() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger runtimeCloses = new AtomicInteger();

        int exit = AlgorithmDebugMcpMain.run(
                new String[] {
                        "--workspace", directory.resolve("workspace").toString(),
                        "--project", directory.resolve("project").toString()
                },
                new ByteArrayInputStream(new byte[0]), stdout,
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (workspace, project, input, output) -> AlgorithmDebugMcpServer.open(
                        input, output, runtimeCloses::incrementAndGet, workspace, project),
                hook -> {
                    throw new IllegalStateException(
                            "hook registration failed at C:\\secret\\token");
                });

        assertEquals(AlgorithmDebugMcpMain.EXIT_STARTUP_FAILURE, exit);
        assertEquals(1, runtimeCloses.get());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("MCP_SERVER_START_FAILED"));
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("secret"));
    }

    @Test
    void shutdownHookSanitizesCloseFailureDiagnostics() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicReference<Thread> hook = new AtomicReference<>();

        int exit = AlgorithmDebugMcpMain.run(
                new String[] {
                        "--workspace", directory.resolve("workspace").toString(),
                        "--project", directory.resolve("project").toString()
                },
                new ByteArrayInputStream(new byte[0]), stdout,
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (workspace, project, input, output) -> AlgorithmDebugMcpServer.open(
                        input, output,
                        () -> {
                            throw new IllegalStateException("C:\\secret\\runtime");
                        },
                        workspace, project),
                hook::set);

        assertEquals(0, exit);
        hook.get().run();
        String diagnostics = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("MCP_SERVER_SHUTDOWN_FAILED"));
        assertFalse(diagnostics.contains("secret"));
    }

    @Test
    void oneServerInstanceIsBoundToOneFrozenProjectRoot() throws Exception {
        Path workspace = directory.resolve("workspace");
        Path project = directory.resolve("project");
        try (ProtocolHarness harness = openHarness(workspace, project)) {
            assertEquals(workspace.toAbsolutePath().normalize(), harness.server.workspaceRoot());
            assertEquals(project.toAbsolutePath().normalize(), harness.server.projectRoot());
            assertTrue(harness.server.isBoundProjectRoot(project.resolve(".")));
            assertFalse(harness.server.isBoundProjectRoot(directory.resolve("another-project")));
        }
    }

    @Test
    void productionBootstrapDoesNotCreateWorkspaceOrProjectState() throws Exception {
        Path workspace = directory.resolve("uninitialized-workspace");
        Path project = directory.resolve("target-project");
        PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        LineOutputStream serverOutput = new LineOutputStream();

        AlgorithmDebugMcpServer server = new McpServerBootstrap().start(
                workspace, project, Map.of(), serverInput, serverOutput);
        try {
            assertFalse(Files.exists(workspace));
            assertFalse(Files.exists(project));
            assertEquals(workspace.toAbsolutePath().normalize(), server.workspaceRoot());
            assertEquals(project.toAbsolutePath().normalize(), server.projectRoot());
        } finally {
            clientOutput.close();
            server.close();
        }
    }

    private ProtocolHarness openHarness() throws IOException {
        return openHarness(directory.resolve("workspace"), directory.resolve("project"));
    }

    private ProtocolHarness openHarness(Path workspace, Path project) throws IOException {
        PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        LineOutputStream serverOutput = new LineOutputStream();
        AlgorithmDebugMcpServer server = AlgorithmDebugMcpServer.open(
                serverInput, serverOutput, () -> { }, workspace, project);
        return new ProtocolHarness(server, clientOutput, serverOutput);
    }

    private static final class ProtocolHarness implements AutoCloseable {
        private final AlgorithmDebugMcpServer server;
        private final PipedOutputStream clientOutput;
        private final LineOutputStream serverOutput;

        private ProtocolHarness(
                AlgorithmDebugMcpServer server,
                PipedOutputStream clientOutput,
                LineOutputStream serverOutput) {
            this.server = server;
            this.clientOutput = clientOutput;
            this.serverOutput = serverOutput;
        }

        private void send(String json) throws IOException {
            sendRaw((json.strip() + '\n').getBytes(StandardCharsets.UTF_8));
        }

        private void sendRaw(byte[] bytes) throws IOException {
            clientOutput.write(bytes);
            clientOutput.flush();
        }

        private String awaitLine() throws InterruptedException {
            String line = serverOutput.lines.poll(5, TimeUnit.SECONDS);
            if (line == null) {
                throw new AssertionError("MCP response was not written within the test budget");
            }
            return line;
        }

        private boolean hasAdditionalLine() {
            return !serverOutput.lines.isEmpty();
        }

        @Override
        public void close() throws Exception {
            clientOutput.close();
            server.close();
        }
    }

    private static final class LineOutputStream extends OutputStream {
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();
        private final java.util.List<String> pendingLines = new java.util.ArrayList<>();

        @Override
        public synchronized void write(int value) {
            if (value == '\n') {
                pendingLines.add(current.toString(StandardCharsets.UTF_8));
                current.reset();
            } else {
                current.write(value);
            }
        }

        @Override
        public synchronized void flush() {
            lines.addAll(pendingLines);
            pendingLines.clear();
        }
    }
}
