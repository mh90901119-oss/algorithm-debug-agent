package org.example.algorithmdebug.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Algorithm Debug Agent 的 Java 原生 stdio MCP 进程入口。 */
public final class AlgorithmDebugMcpMain {
    /** 参数错误退出码。 */
    public static final int EXIT_INVALID_ARGUMENTS = 2;
    /** 启动失败退出码。 */
    public static final int EXIT_STARTUP_FAILURE = 10;
    private static final int EXIT_SUCCESS = 0;
    private static final String WORKSPACE_OPTION = "--workspace";
    private static final String PROJECT_OPTION = "--project";
    private static final String INVALID_ARGUMENTS = "MCP_SERVER_INVALID_ARGUMENTS";
    private static final String STARTUP_FAILURE = "MCP_SERVER_START_FAILED";
    private static final String SHUTDOWN_FAILURE = "MCP_SERVER_SHUTDOWN_FAILED";
    private static final String SHUTDOWN_HOOK_NAME = "algorithm-debug-mcp-shutdown";
    private static final int EXPECTED_ARGUMENT_COUNT = 4;
    private static final int OPTION_PAIR_SIZE = 2;

    private AlgorithmDebugMcpMain() {
    }

    /** 仅在启动失败时退出；成功后 SDK 的非守护 stdio 会话持有进程。 */
    public static void main(String[] arguments) {
        int exit = run(arguments, System.in, System.out, System.err);
        if (exit != EXIT_SUCCESS) {
            System.exit(exit);
        }
    }

    static int run(
            String[] arguments,
            InputStream input,
            OutputStream protocolOutput,
            PrintStream diagnostics) {
        return run(
                arguments, input, protocolOutput, diagnostics,
                (workspace, project, serverInput, serverOutput) ->
                        new McpServerBootstrap().start(
                                workspace, project, System.getenv(),
                                serverInput, serverOutput),
                hook -> Runtime.getRuntime().addShutdownHook(hook));
    }

    static int run(
            String[] arguments,
            InputStream input,
            OutputStream protocolOutput,
            PrintStream diagnostics,
            ServerLauncher serverLauncher,
            ShutdownHookRegistrar shutdownHookRegistrar) {
        if (input == null || protocolOutput == null || diagnostics == null) {
            throw new IllegalArgumentException("MCP process streams must not be null");
        }
        if (serverLauncher == null || shutdownHookRegistrar == null) {
            throw new IllegalArgumentException("MCP process collaborators must not be null");
        }
        final LaunchArguments launch;
        try {
            launch = LaunchArguments.parse(arguments);
        } catch (IllegalArgumentException failure) {
            diagnostics.println(INVALID_ARGUMENTS + ": expected --workspace and --project");
            return EXIT_INVALID_ARGUMENTS;
        }
        AlgorithmDebugMcpServer server = null;
        try {
            server = serverLauncher.start(
                    launch.workspaceRoot(), launch.projectRoot(), input, protocolOutput);
            AlgorithmDebugMcpServer startedServer = server;
            shutdownHookRegistrar.register(
                    new Thread(
                            () -> closeForShutdown(startedServer, diagnostics),
                            SHUTDOWN_HOOK_NAME));
            return EXIT_SUCCESS;
        } catch (RuntimeException failure) {
            closeAfterFailedStart(server, failure);
            diagnostics.println(STARTUP_FAILURE + ": server bootstrap failed");
            return EXIT_STARTUP_FAILURE;
        }
    }

    private static void closeAfterFailedStart(
            AlgorithmDebugMcpServer server, RuntimeException startupFailure) {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (RuntimeException closeFailure) {
            startupFailure.addSuppressed(closeFailure);
        }
    }

    private static void closeForShutdown(
            AlgorithmDebugMcpServer server, PrintStream diagnostics) {
        try {
            server.close();
        } catch (RuntimeException failure) {
            diagnostics.println(SHUTDOWN_FAILURE + ": server close failed");
        }
    }

    @FunctionalInterface
    interface ServerLauncher {
        AlgorithmDebugMcpServer start(
                Path workspaceRoot,
                Path projectRoot,
                InputStream input,
                OutputStream output);
    }

    @FunctionalInterface
    interface ShutdownHookRegistrar {
        void register(Thread hook);
    }

    private record LaunchArguments(Path workspaceRoot, Path projectRoot) {
        private static LaunchArguments parse(String[] arguments) {
            if (arguments == null || arguments.length != EXPECTED_ARGUMENT_COUNT) {
                throw new IllegalArgumentException("Exactly two path options are required");
            }
            Map<String, Path> values = new LinkedHashMap<>();
            for (int index = 0; index < arguments.length; index += OPTION_PAIR_SIZE) {
                String option = arguments[index];
                if ((!WORKSPACE_OPTION.equals(option) && !PROJECT_OPTION.equals(option))
                        || values.containsKey(option)) {
                    throw new IllegalArgumentException("Unknown or duplicate MCP option");
                }
                try {
                    values.put(option, Path.of(arguments[index + 1]));
                } catch (InvalidPathException failure) {
                    throw new IllegalArgumentException("MCP path option is invalid", failure);
                }
            }
            if (!values.containsKey(WORKSPACE_OPTION) || !values.containsKey(PROJECT_OPTION)) {
                throw new IllegalArgumentException("Both MCP path options are required");
            }
            return new LaunchArguments(
                    values.get(WORKSPACE_OPTION), values.get(PROJECT_OPTION));
        }
    }
}
