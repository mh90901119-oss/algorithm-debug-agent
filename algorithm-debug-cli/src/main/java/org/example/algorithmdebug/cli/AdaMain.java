package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.contracts.ToolResponse;
import org.example.algorithmdebug.contracts.DoctorCheck;
import org.example.algorithmdebug.contracts.DoctorStatus;
import org.example.algorithmdebug.core.CaseRunException;
import org.example.algorithmdebug.core.ControlPlaneException;
import org.example.algorithmdebug.core.ControlPlaneServices;
import org.example.algorithmdebug.core.ArtifactBackedResult;
import org.example.algorithmdebug.core.MavenExecutableLocator;
import org.example.algorithmdebug.core.MultiArtifactBackedResult;
import org.example.algorithmdebug.core.ToolDoctorProbe;
import org.example.algorithmdebug.core.JdwpToolConfiguration;
import org.example.algorithmdebug.plan.PlanCompilationException;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisException;
import org.example.algorithmdebug.codepath.CodePathProcessCollector;
import org.example.algorithmdebug.codepath.CodePathToolConfiguration;
import org.example.algorithmdebug.codepath.CodePathAdapterException;
import org.example.algorithmdebug.codepath.MavenTestClasspathResolver;
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.jdwp.JdwpCollectionCoordinator;
import org.example.algorithmdebug.jdwp.LoopbackPortAllocator;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;
import org.example.algorithmdebug.casecore.logging.JavaExecutionLogRouter;

import java.io.File;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.util.Map;

/** Algorithm Debug Agent 的稳定 JSON CLI 入口。 */
public final class AdaMain {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_INVALID_ARGUMENTS = 2;
    private static final int EXIT_DOMAIN_FAILURE = 3;
    private static final int EXIT_INTERNAL_ERROR = 10;

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
     * @return 0 成功、2 参数错误、3 确定性领域错误、10 未预期错误
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
            logContext = CliLogContextResolver.after(command, result);
            executionLog.info(logContext, "AdaMain", "CLI_INVOCATION_COMPLETED", "COMPLETED",
                    "CLI invocation completed", Map.of("command", commandName));
            if (result instanceof ArtifactBackedResult<?> artifactBacked) {
                responseWriter.write(ToolResponse.success(
                        artifactBacked.summary(), List.of(artifactBacked.artifact())), stdout);
            } else if (result instanceof MultiArtifactBackedResult<?> artifactBacked) {
                responseWriter.write(ToolResponse.success(
                        artifactBacked.summary(), artifactBacked.artifacts()), stdout);
            } else {
                responseWriter.write(ToolResponse.success(result, List.of()), stdout);
            }
            return EXIT_SUCCESS;
        } catch (CliInputException failure) {
            logFailure(logContext, commandName, "CLI_INVALID_ARGUMENTS", failure);
            responseWriter.write(
                    ToolResponse.failure(
                            "CLI_INVALID_ARGUMENTS",
                            "Invalid CLI input: " + failure.getMessage(), List.of()),
                    stdout);
            return EXIT_INVALID_ARGUMENTS;
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
        } catch (RuntimeException failure) {
            logFailure(logContext, commandName, "INTERNAL_ERROR", failure);
            responseWriter.write(
                    ToolResponse.failure(
                            "INTERNAL_ERROR", CliFailureMessages.forCode("INTERNAL_ERROR"), List.of()),
                    stdout);
            return EXIT_INTERNAL_ERROR;
        }
    }

    static AdaMain defaultApplication() {
        AgentExecutionLog log = JavaExecutionLogRouter.fromEnvironment(
                Clock.systemDefaultZone(), System.getenv());
        CliCommandExecutor executor = defaultExecutor(log);
        return new AdaMain(executor::execute, new CliResponseWriter(), log);
    }

    /** 装配默认 CLI 执行器；保留包级测试缝以验证组合根。 */
    static CliCommandExecutor defaultExecutor() {
        return defaultExecutor(AgentExecutionLog.disabled());
    }

    private static CliCommandExecutor defaultExecutor(AgentExecutionLog executionLog) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        List<TargetProjectAdapter> adapters = List.copyOf(ServiceLoader
                .load(TargetProjectAdapter.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(adapter -> (TargetProjectAdapter) adapter)
                .collect(Collectors.toList()));
        MavenExecutableLocator mavenLocator = new MavenExecutableLocator(
                System.getenv(), File.pathSeparator, windows);
        java.nio.file.Path agentJavaExecutable = java.nio.file.Path.of(
                System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
        RuntimeToolchain toolchain = RuntimeToolchain.resolve(
                System.getenv(), agentJavaExecutable, windows);
        ConfiguredCodePath codePath = configuredCodePath(toolchain.targetJavaExecutable());
        ConfiguredJdwp jdwp = configuredJdwp(toolchain.agentJavaExecutable());
        ControlPlaneServices services = ControlPlaneServices.create(
                Clock.systemUTC(),
                () -> Runtime.version().feature(),
                System.getenv(),
                File.pathSeparator,
                windows,
                adapters,
                mavenLocator.locate(toolchain.mavenExecutable()),
                codePath.collector(),
                new MavenTestClasspathResolver(),
                jdwp.tool(), jdwp.executor(), jdwp.ports(),
                codePath.doctorProbe(), jdwp.doctorProbe(), executionLog);
        return new CliCommandExecutor(
                services.workspace(), services.project(), services.doctor(),
                services.cases(), services.runs(), services.staticAnalysis(), services.collections(),
                services.jdwpCollections(), services.algorithmInputs());
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

    private static ConfiguredCodePath configuredCodePath(java.nio.file.Path javaExecutable) {
        String jar = System.getenv("ADA_CODEPATH_LAUNCHER_JAR");
        if (jar == null || jar.isBlank()) {
            MethodPathCollector unavailable = request -> {
                throw new org.example.algorithmdebug.methodpath.MethodPathCollectionException(
                        "CODEPATH_TOOL_NOT_CONFIGURED", "CodePath launcher is not configured", null);
            };
            return new ConfiguredCodePath(unavailable, () -> new DoctorCheck(
                    "codepath", DoctorStatus.FAIL, "CODEPATH_TOOL_NOT_CONFIGURED",
                    "CodePath launcher is not configured"));
        }
        CodePathToolConfiguration configuration = new CodePathToolConfiguration(
                javaExecutable, java.nio.file.Path.of(jar), "0.1.0-SNAPSHOT",
                "org.example.algorithmdebug.codepath.launcher.ExternalJUnitTraceLauncher");
        ToolDoctorProbe probe = () -> {
            if (!java.nio.file.Files.isRegularFile(javaExecutable)) {
                return new DoctorCheck(
                        "codepath", DoctorStatus.FAIL, "CODEPATH_JAVA_MISSING",
                        "CodePath Java executable is unavailable");
            }
            try {
                configuration.verifyTool();
                return new DoctorCheck(
                        "codepath", DoctorStatus.PASS, "CODEPATH_TOOL_OK",
                        "CodePath launcher configuration and file checks passed");
            } catch (CodePathAdapterException failure) {
                return new DoctorCheck(
                        "codepath", DoctorStatus.FAIL, failure.code(),
                        "CodePath launcher configuration validation failed");
            }
        };
        return new ConfiguredCodePath(new CodePathProcessCollector(configuration), probe);
    }

    private record ConfiguredCodePath(
            MethodPathCollector collector,
            ToolDoctorProbe doctorProbe) {
    }

    private static ConfiguredJdwp configuredJdwp(java.nio.file.Path javaExecutable) {
        String jar = System.getenv("ADA_JDWP_COLLECTOR_JAR");
        if (jar == null || jar.isBlank()) {
            JdwpToolConfiguration unavailable = new JdwpToolConfiguration(
                    javaExecutable, "unavailable");
            return new ConfiguredJdwp(
                    unavailable,
                    request -> { throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                            "JDWP_TOOL_NOT_CONFIGURED", "JDWP Collector is not configured", null); },
                    () -> 51234,
                    () -> new DoctorCheck(
                            "jdwp", DoctorStatus.FAIL, "JDWP_TOOL_NOT_CONFIGURED",
                            "JDWP Collector is not configured"));
        }
        JdwpToolConfiguration tool = new JdwpToolConfiguration(
                java.nio.file.Path.of(jar), "2.0.0");
        ToolDoctorProbe probe = () -> {
            if (!Files.isRegularFile(tool.collectorJar())) {
                return new DoctorCheck(
                        "jdwp", DoctorStatus.FAIL, "JDWP_TOOL_MISSING",
                        "JDWP Collector JAR is unavailable");
            }
            return new DoctorCheck(
                    "jdwp", DoctorStatus.PASS, "JDWP_TOOL_OK",
                    "JDWP Collector JAR path is available");
        };
        JdwpCollectionCoordinator coordinator = new JdwpCollectionCoordinator();
        LoopbackPortAllocator ports = new LoopbackPortAllocator();
        return new ConfiguredJdwp(tool, coordinator::execute, ports::allocate, probe);
    }

    private record ConfiguredJdwp(
            JdwpToolConfiguration tool,
            org.example.algorithmdebug.core.JdwpCollectionExecutor executor,
            org.example.algorithmdebug.core.JdwpPortProvider ports,
            ToolDoctorProbe doctorProbe) {
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
