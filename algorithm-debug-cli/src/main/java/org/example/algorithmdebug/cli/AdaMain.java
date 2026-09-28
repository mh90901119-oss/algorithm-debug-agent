package org.example.algorithmdebug.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;
import org.example.algorithmdebug.casecore.logging.JavaExecutionLogRouter;
import org.example.algorithmdebug.contracts.ToolResponse;
import org.example.algorithmdebug.core.ArtifactBackedResult;
import org.example.algorithmdebug.core.CaseRunException;
import org.example.algorithmdebug.core.ControlPlaneException;
import org.example.algorithmdebug.core.MultiArtifactBackedResult;
import org.example.algorithmdebug.core.coordination.AnalysisCoordinator;
import org.example.algorithmdebug.plan.PlanCompilationException;
import org.example.algorithmdebug.runtime.AlgorithmDebugRuntime;
import org.example.algorithmdebug.runtime.AlgorithmDebugRuntimeBootstrap;
import org.example.algorithmdebug.runtime.RuntimeBootstrapException;
import org.example.algorithmdebug.runtime.RuntimeBootstrapRequest;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisException;

/** Algorithm Debug Agent 的稳定 JSON CLI 入口。 */
public final class AdaMain {
    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_INVALID_ARGUMENTS = 2;
    private static final int EXIT_DOMAIN_FAILURE = 3;
    private static final int EXIT_INTERNAL_ERROR = 10;
    private static final String WORKSPACE_ID_PREFIX = "workspace-";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final CommandExecution execution;
    private final CliResponseWriter responseWriter;
    private final AgentExecutionLog executionLog;

    AdaMain(CommandExecution execution, CliResponseWriter responseWriter) {
        this(execution, responseWriter, AgentExecutionLog.disabled());
    }

    AdaMain(
            CommandExecution execution,
            CliResponseWriter responseWriter,
            AgentExecutionLog executionLog) {
        if (execution == null || responseWriter == null || executionLog == null) {
            throw new IllegalArgumentException("CLI dependencies must not be null");
        }
        this.execution = execution;
        this.responseWriter = responseWriter;
        this.executionLog = executionLog;
    }

    /** JVM 主入口；退出码由 {@link #run(String[], PrintStream, PrintStream)} 返回。 */
    public static void main(String[] arguments) {
        System.exit(launch(arguments, System.out, System.err, AdaMain::defaultApplication));
    }

    static int launch(
            String[] arguments,
            PrintStream stdout,
            PrintStream stderr,
            ApplicationBootstrap bootstrap) {
        if (stdout == null || stderr == null || bootstrap == null) {
            throw new IllegalArgumentException("CLI launch dependencies must not be null");
        }
        try {
            return bootstrap.create().run(arguments, stdout, stderr);
        } catch (CliStartupException failure) {
            logBootstrapFailure(failure.code(), failure);
            new CliResponseWriter().write(ToolResponse.failure(
                    failure.code(), CliFailureMessages.forCode(failure.code()), List.of()), stdout);
            return EXIT_INTERNAL_ERROR;
        } catch (RuntimeException failure) {
            logBootstrapFailure("CLI_BOOTSTRAP_FAILED", failure);
            new CliResponseWriter().write(ToolResponse.failure(
                    "CLI_BOOTSTRAP_FAILED",
                    CliFailureMessages.forCode("CLI_BOOTSTRAP_FAILED"), List.of()), stdout);
            return EXIT_INTERNAL_ERROR;
        }
    }

    /**
     * 解析并执行一个命令，stdout 始终只写一个 ToolResponse JSON 文档。
     *
     * @param arguments CLI 参数
     * @param stdout 标准输出
     * @param stderr 标准错误
     * @return 0 成功、2 输入错误、3 可处理领域失败、10 内部或启动失败
     */
    public int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        if (stdout == null || stderr == null) {
            throw new IllegalArgumentException("stdout and stderr must not be null");
        }
        CliCommand command;
        try {
            command = CliArguments.parse(arguments);
        } catch (IllegalArgumentException failure) {
            executionLog.error(
                    AgentLogContext.bootstrap(), "AdaMain", "CLI_ARGUMENTS_REJECTED", "REJECTED",
                    "CLI arguments were rejected", Map.of("code", "CLI_INVALID_ARGUMENTS"), failure);
            responseWriter.write(
                    ToolResponse.failure(
                            "CLI_INVALID_ARGUMENTS",
                            "Invalid CLI arguments: " + failure.getMessage(), List.of()),
                    stdout);
            return EXIT_INVALID_ARGUMENTS;
        }

        AgentLogContext logContext = CliLogContextResolver.before(command);
        String commandName = CliLogContextResolver.commandName(command);
        executionLog.info(logContext, "AdaMain", "CLI_INVOCATION_STARTED", "STARTED",
                "CLI invocation started", Map.of("command", commandName));
        try {
            Object result = execution.execute(command);
            ToolResponse<?> response = normalizeResponse(result);
            logContext = CliLogContextResolver.after(command, response);
            if (response.success()) {
                executionLog.info(
                        logContext, "AdaMain", "CLI_INVOCATION_COMPLETED", "COMPLETED",
                        "CLI invocation completed", Map.of("command", commandName));
            } else {
                executionLog.warn(
                        logContext, "AdaMain", "CLI_INVOCATION_RETURNED_FAILURE", "FAILED",
                        "CLI invocation returned a structured failure",
                        Map.of("command", commandName, "code", response.code()));
            }
            responseWriter.write(response, stdout);
            return response.success() ? EXIT_SUCCESS : EXIT_DOMAIN_FAILURE;
        } catch (CliInputException failure) {
            logFailure(logContext, commandName, "CLI_INVALID_ARGUMENTS", failure);
            responseWriter.write(
                    ToolResponse.failure(
                            "CLI_INVALID_ARGUMENTS",
                            "Invalid CLI input: " + failure.getMessage(), List.of()),
                    stdout);
            return EXIT_INVALID_ARGUMENTS;
        } catch (AnalysisCoordinator.ExecutionFailure failure) {
            ToolResponse<?> domainFailure = coordinatedDomainFailure(failure.getCause());
            if (domainFailure != null) {
                logFailure(logContext, commandName, domainFailure.code(), failure);
                responseWriter.write(domainFailure, stdout);
                return EXIT_DOMAIN_FAILURE;
            }
            String code = failure.code().name();
            logFailure(logContext, commandName, code, failure);
            responseWriter.write(
                    ToolResponse.failure(code, CliFailureMessages.forCode(code), List.of()), stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (CaseRunException failure) {
            logFailure(logContext, commandName, failure.code(), failure);
            responseWriter.write(
                    ToolResponse.failure(
                            failure.code(), CliFailureMessages.forCaseRun(failure),
                            failure.artifacts()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (ControlPlaneException failure) {
            logFailure(logContext, commandName, failure.code(), failure);
            responseWriter.write(
                    ToolResponse.failure(
                            failure.code(), CliFailureMessages.forCode(failure.code()), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (StaticAnalysisException failure) {
            logFailure(logContext, commandName, failure.code(), failure);
            responseWriter.write(
                    ToolResponse.failure(
                            failure.code(), CliFailureMessages.forCode(failure.code()), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (PlanCompilationException failure) {
            logFailure(logContext, commandName, "PLAN_COMPILATION_FAILED", failure);
            responseWriter.write(
                    ToolResponse.failure(
                            "PLAN_COMPILATION_FAILED",
                            CliFailureMessages.forPlanCompilation(
                                    "PLAN_COMPILATION_FAILED", failure), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (CliStartupException failure) {
            logFailure(logContext, commandName, failure.code(), failure);
            responseWriter.write(
                    ToolResponse.failure(
                            failure.code(), CliFailureMessages.forCode(failure.code()), List.of()),
                    stdout);
            return EXIT_INTERNAL_ERROR;
        } catch (RuntimeException failure) {
            logFailure(logContext, commandName, "INTERNAL_ERROR", failure);
            responseWriter.write(
                    ToolResponse.failure(
                            "INTERNAL_ERROR", CliFailureMessages.forCode("INTERNAL_ERROR"),
                            List.of()),
                    stdout);
            return EXIT_INTERNAL_ERROR;
        }
    }

    static AdaMain defaultApplication() {
        AgentExecutionLog log = JavaExecutionLogRouter.fromEnvironment(
                Clock.systemDefaultZone(), System.getenv());
        return new AdaMain(
                command -> executeWithRuntime(command, log),
                new CliResponseWriter(),
                log);
    }

    private static ToolResponse<?> normalizeResponse(Object result) {
        if (result instanceof ToolResponse<?> response) {
            return response;
        }
        if (result instanceof ArtifactBackedResult<?> value) {
            return ToolResponse.success(value.summary(), List.of(value.artifact()));
        }
        if (result instanceof MultiArtifactBackedResult<?> value) {
            return ToolResponse.success(value.summary(), value.artifacts());
        }
        return ToolResponse.success(result, List.of());
    }

    private static ToolResponse<?> coordinatedDomainFailure(Throwable cause) {
        if (cause instanceof CaseRunException failure) {
            return ToolResponse.failure(
                    failure.code(), CliFailureMessages.forCaseRun(failure), failure.artifacts());
        }
        if (cause instanceof ControlPlaneException failure) {
            return ToolResponse.failure(
                    failure.code(), CliFailureMessages.forCode(failure.code()), List.of());
        }
        if (cause instanceof StaticAnalysisException failure) {
            return ToolResponse.failure(
                    failure.code(), CliFailureMessages.forCode(failure.code()), List.of());
        }
        if (cause instanceof PlanCompilationException failure) {
            return ToolResponse.failure(
                    "PLAN_COMPILATION_FAILED",
                    CliFailureMessages.forPlanCompilation(
                            "PLAN_COMPILATION_FAILED", failure), List.of());
        }
        if (cause instanceof WorkspaceException failure) {
            return ToolResponse.failure(
                    failure.code(), CliFailureMessages.forCode(failure.code()), List.of());
        }
        return null;
    }

    private static ToolResponse<?> executeWithRuntime(
            CliCommand command, AgentExecutionLog executionLog) {
        Path workspace = command.workspaceRoot();
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        Path agentJava = Path.of(
                System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        RuntimeBootstrapRequest request = new RuntimeBootstrapRequest(
                stableWorkspaceId(workspace),
                workspace,
                System.getenv(),
                Clock.systemUTC(),
                () -> Runtime.version().feature(),
                agentJava,
                File.pathSeparator,
                windows,
                executionLog);
        try (AlgorithmDebugRuntime runtime =
                     new AlgorithmDebugRuntimeBootstrap().bootstrap(request)) {
            CliActionRequestMapper mapper = new CliActionRequestMapper(
                    request.workspaceId(),
                    request.workspaceRoot(),
                    new CliAnalysisContextResolver(request.workspaceRoot()),
                    request.clock(),
                    () -> UUID.randomUUID().toString());
            return new CliCommandExecutor(runtime.services(), mapper).execute(command);
        } catch (RuntimeBootstrapException failure) {
            throw new CliStartupException(
                    failure.code(), "Shared runtime could not be initialized", failure);
        }
    }

    static String stableWorkspaceId(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        final Path canonical;
        try {
            canonical = workspace.toFile().getCanonicalFile().toPath();
        } catch (IOException failure) {
            throw new CliStartupException(
                    "CLI_WORKSPACE_CANONICALIZATION_FAILED",
                    "Workspace identity could not be derived", failure);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(
                    canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return WORKSPACE_ID_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(HASH_ALGORITHM + " is unavailable", impossible);
        }
    }

    private void logFailure(
            AgentLogContext context, String command, String code, Throwable failure) {
        executionLog.error(context, "AdaMain", "CLI_INVOCATION_FAILED", "FAILED",
                "CLI invocation failed", Map.of("command", command, "code", code), failure);
    }

    private static void logBootstrapFailure(String code, RuntimeException failure) {
        try {
            AgentExecutionLog log = JavaExecutionLogRouter.fromEnvironment(
                    Clock.systemDefaultZone(), System.getenv());
            log.error(AgentLogContext.bootstrap(), "AdaMain", "CLI_BOOTSTRAP_FAILED", "FAILED",
                    "CLI bootstrap failed", Map.of("code", code), failure);
        } catch (RuntimeException ignored) {
            // 日志失败不得破坏 stdout 的单 ToolResponse 协议。
        }
    }
}

@FunctionalInterface
interface CommandExecution {
    Object execute(CliCommand command);
}

@FunctionalInterface
interface ApplicationBootstrap {
    AdaMain create();
}
