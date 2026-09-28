package org.example.algorithmdebug.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 一个绑定冻结 Workspace/Project 根的本地 stdio MCP Server。 */
public final class AlgorithmDebugMcpServer implements AutoCloseable {
    /** 协议协商中稳定的 Server 名称。 */
    public static final String SERVER_NAME = "algorithm-debug-agent";
    /** 协议协商中稳定的 Server 实现版本。 */
    public static final String SERVER_VERSION = "0.1.0";

    private final Path workspaceRoot;
    private final Path projectRoot;
    private final McpSyncServer sdkServer;
    private final McpServerLifecycle lifecycle;

    private AlgorithmDebugMcpServer(
            Path workspaceRoot,
            Path projectRoot,
            McpSyncServer sdkServer,
            McpServerLifecycle lifecycle) {
        this.workspaceRoot = workspaceRoot;
        this.projectRoot = projectRoot;
        this.sdkServer = sdkServer;
        this.lifecycle = lifecycle;
    }

    static AlgorithmDebugMcpServer open(
            InputStream input,
            OutputStream protocolOutput,
            AutoCloseable runtime,
            Path workspaceRoot,
            Path projectRoot) {
        if (input == null || protocolOutput == null || runtime == null) {
            throw new IllegalArgumentException("MCP server dependencies must not be null");
        }
        Path checkedWorkspace = checkedRoot(workspaceRoot, "workspaceRoot");
        Path checkedProject = checkedRoot(projectRoot, "projectRoot");
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        DeferredServerCloser deferredServerCloser = new DeferredServerCloser();
        McpServerLifecycle lifecycle = new McpServerLifecycle(
                McpServerLimits.DEFAULT_SHUTDOWN_TIMEOUT,
                McpServerLimits.DEFAULT_ACTIVE_CALLS,
                runtime,
                deferredServerCloser);
        lifecycle.start();
        McpProtocolOutputStream guardedOutput = new McpProtocolOutputStream(
                protocolOutput, jsonMapper, lifecycle, McpServerLimits.MAX_REQUEST_BYTES);
        McpProtocolInputStream guardedInput = new McpProtocolInputStream(
                input, guardedOutput, jsonMapper, new McpProtocolErrorMapper(), lifecycle,
                McpServerLimits.MAX_REQUEST_BYTES);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                jsonMapper, guardedInput, guardedOutput, McpServerLimits.MAX_REQUEST_BYTES);
        final McpSyncServer sdkServer;
        try {
            sdkServer = McpServer.sync(transport)
                    .serverInfo(SERVER_NAME, SERVER_VERSION)
                    .capabilities(ServerCapabilities.builder().build())
                    .requestTimeout(McpServerLimits.DEFAULT_REQUEST_TIMEOUT)
                    .build();
            deferredServerCloser.bind(sdkServer::close);
        } catch (RuntimeException failure) {
            closeAfterFailedStart(lifecycle, failure);
            throw failure;
        }
        return new AlgorithmDebugMcpServer(
                checkedWorkspace, checkedProject, sdkServer, lifecycle);
    }

    /** @return Server 启动时冻结的归档 Workspace 根 */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** @return Server 启动时冻结的目标 Project 根 */
    public Path projectRoot() {
        return projectRoot;
    }

    /** 后续请求上下文用此方法与冻结 Project 根做精确规范路径匹配。 */
    public boolean isBoundProjectRoot(Path candidate) {
        return candidate != null
                && projectRoot.equals(candidate.toAbsolutePath().normalize());
    }

    /** @return 活动调用与关闭控制的唯一生命周期实例 */
    public McpServerLifecycle lifecycle() {
        return lifecycle;
    }

    McpSyncServer sdkServer() {
        return sdkServer;
    }

    @Override
    public void close() {
        lifecycle.close();
    }

    private static Path checkedRoot(Path root, String name) {
        if (root == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException(name + " must not be a filesystem root");
        }
        return normalized;
    }

    private static void closeAfterFailedStart(
            McpServerLifecycle lifecycle, RuntimeException failure) {
        try {
            lifecycle.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static final class DeferredServerCloser implements Runnable {
        private final AtomicReference<Runnable> delegate = new AtomicReference<>();
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private final AtomicBoolean delegateClosed = new AtomicBoolean();

        private void bind(Runnable serverCloser) {
            if (serverCloser == null || !delegate.compareAndSet(null, serverCloser)) {
                throw new IllegalStateException("MCP SDK server closer can only be bound once");
            }
            closeBoundDelegate();
        }

        @Override
        public void run() {
            if (closeRequested.compareAndSet(false, true)) {
                closeBoundDelegate();
            }
        }

        private void closeBoundDelegate() {
            Runnable serverCloser = delegate.get();
            if (closeRequested.get() && serverCloser != null
                    && delegateClosed.compareAndSet(false, true)) {
                serverCloser.run();
            }
        }
    }
}
