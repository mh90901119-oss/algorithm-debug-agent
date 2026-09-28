package org.example.algorithmdebug.mcp;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.JavaExecutionLogRouter;
import org.example.algorithmdebug.runtime.AlgorithmDebugRuntime;
import org.example.algorithmdebug.runtime.AlgorithmDebugRuntimeBootstrap;
import org.example.algorithmdebug.runtime.RuntimeBootstrapRequest;
import org.example.algorithmdebug.runtime.RuntimeWorkspaceIdentity;

/** 组装一次长驻 stdio MCP Server 与唯一共享 Runtime。 */
public final class McpServerBootstrap {
    private static final String OS_NAME_PROPERTY = "os.name";
    private static final String WINDOWS_MARKER = "win";
    private static final String JAVA_HOME_PROPERTY = "java.home";
    private static final String JAVA_BIN_DIRECTORY = "bin";
    private static final String WINDOWS_JAVA_EXECUTABLE = "java.exe";
    private static final String JAVA_EXECUTABLE = "java";

    private final AlgorithmDebugRuntimeBootstrap runtimeBootstrap;

    /** 使用生产共享 Runtime 组合根。 */
    public McpServerBootstrap() {
        this(new AlgorithmDebugRuntimeBootstrap());
    }

    McpServerBootstrap(AlgorithmDebugRuntimeBootstrap runtimeBootstrap) {
        if (runtimeBootstrap == null) {
            throw new IllegalArgumentException("runtimeBootstrap must not be null");
        }
        this.runtimeBootstrap = runtimeBootstrap;
    }

    /**
     * 从可信启动配置建立 MCP Server；不会初始化 Workspace 或注册 Project。
     *
     * @param workspaceRoot 归档 Workspace 根
     * @param projectRoot 此 Server 唯一绑定的目标 Project 根
     * @param environment 受限 Agent 环境
     * @param input stdin
     * @param output stdout，仅用于协议帧
     * @return 已启动 Server
     */
    public AlgorithmDebugMcpServer start(
            Path workspaceRoot,
            Path projectRoot,
            Map<String, String> environment,
            InputStream input,
            OutputStream output) {
        if (environment == null || input == null || output == null
                || environment.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("MCP bootstrap inputs are invalid");
        }
        boolean windows = System.getProperty(OS_NAME_PROPERTY, "")
                .toLowerCase(Locale.ROOT)
                .contains(WINDOWS_MARKER);
        Path agentJava = Path.of(
                System.getProperty(JAVA_HOME_PROPERTY), JAVA_BIN_DIRECTORY,
                windows ? WINDOWS_JAVA_EXECUTABLE : JAVA_EXECUTABLE);
        Clock clock = Clock.systemUTC();
        AgentExecutionLog executionLog = JavaExecutionLogRouter.fromEnvironment(
                clock, environment);
        RuntimeBootstrapRequest request = new RuntimeBootstrapRequest(
                RuntimeWorkspaceIdentity.derive(workspaceRoot),
                workspaceRoot,
                environment,
                clock,
                () -> Runtime.version().feature(),
                agentJava,
                File.pathSeparator,
                windows,
                executionLog);
        AlgorithmDebugRuntime runtime = runtimeBootstrap.bootstrap(request);
        try {
            return AlgorithmDebugMcpServer.open(
                    input, output, runtime, request.workspaceRoot(), projectRoot);
        } catch (RuntimeException failure) {
            if (!runtime.closed()) {
                try {
                    runtime.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }
}
