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
        System.exit(defaultApplication().run(arguments, System.out, System.err));
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
                    ToolResponse.failure(failure.code(), publicDomainMessage(failure), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (ControlPlaneException failure) {
            logFailure(logContext, commandName, failure.code(), failure);
            responseWriter.write(
                    ToolResponse.failure(failure.code(), safeDomainMessage(failure.code()), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (StaticAnalysisException failure) {
            logFailure(logContext, commandName, failure.code(), failure);
            responseWriter.write(
                    ToolResponse.failure(
                            failure.code(), safeDomainMessage(failure.code()), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (PlanCompilationException failure) {
            logFailure(logContext, commandName, "PLAN_COMPILATION_FAILED", failure);
            responseWriter.write(
                    ToolResponse.failure(
                            "PLAN_COMPILATION_FAILED",
                            planCompilationMessage("PLAN_COMPILATION_FAILED", failure), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (RuntimeException failure) {
            logFailure(logContext, commandName, "INTERNAL_ERROR", failure);
            responseWriter.write(
                    ToolResponse.failure("INTERNAL_ERROR", "Internal Agent error", List.of()),
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

    private static String safeDomainMessage(String code) {
        return switch (code) {
            case "WORKSPACE_PATH_INVALID" -> "Workspace path is invalid";
            case "WORKSPACE_MANIFEST_INVALID" -> "Workspace manifest is invalid";
            case "WORKSPACE_SCHEMA_UNSUPPORTED" -> "Workspace schema version is unsupported";
            case "WORKSPACE_WRITE_FAILED" -> "Workspace write failed";
            case "PROJECT_NOT_MAVEN" -> "Project is not an independent Maven module";
            case "PROJECT_ID_CONFLICT" -> "Project ID conflicts with an existing registration";
            case "PROJECT_PATH_CONFLICT" -> "Project path conflicts with an existing registration";
            case "PROJECT_REGISTRATION_INVALID" -> "Project registration is invalid";
            case "CONFIG_INVALID" -> "Workspace configuration is invalid";
            case "PROJECT_NOT_REGISTERED" -> "Project is not registered";
            case "ADAPTER_NOT_FOUND" -> "No compatible adapter is available";
            case "ADAPTER_AMBIGUOUS" -> "More than one adapter matches the project";
            case "CASE_NOT_FOUND" -> "Case was not found";
            case "CONTEXT_NOT_FOUND" -> "Case context was not found";
            case "ANALYSIS_NOT_FOUND" -> "Case analysis was not found";
            case "ALGORITHM_INPUT_NOT_FOUND" -> "Target UT does not declare one supported algorithm input";
            case "ALGORITHM_INPUT_EXPRESSION_UNSUPPORTED" -> "Algorithm input path must be a direct String literal";
            case "MULTIPLE_ALGORITHM_INPUTS_UNSUPPORTED" -> "Target UT declares multiple algorithm inputs";
            case "ALGORITHM_INPUT_FILE_NOT_FOUND" -> "Configured algorithm input file was not found";
            case "ALGORITHM_INPUT_NOT_REGULAR_FILE" -> "Configured algorithm input is not a regular file";
            case "ALGORITHM_INPUT_TOO_LARGE" -> "Algorithm input exceeds the supported size limit";
            case "ALGORITHM_INPUT_COPY_FAILED" -> "Algorithm input could not be archived";
            case "ANALYSIS_INPUT_NOT_CAPTURED" -> "Capture the current Analysis algorithm input before running the UT";
            case "CASE_ARTIFACT_NOT_REGISTERED" -> "Artifact ID is not registered in this case";
            case "CASE_ARTIFACT_PATH_INVALID" -> "Artifact path is invalid";
            case "CASE_ARTIFACT_OFFSET_INVALID" -> "Artifact offset is invalid";
            case "CASE_ARTIFACT_INTEGRITY_MISMATCH" -> "Artifact content no longer matches its registration";
            case "CASE_ARTIFACT_NOT_UTF8" -> "Artifact is not valid UTF-8 text";
            case "CASE_ARTIFACT_BUDGET_TOO_SMALL" -> "Artifact read budget is too small";
            case "CASE_PROJECT_MISMATCH" -> "Case belongs to another project";
            case "CASE_TARGET_TEST_MISMATCH" -> "Case belongs to another target test";
            case "MAVEN_NOT_FOUND" -> "Maven executable is unavailable";
            case "RUN_ARCHIVE_WRITE_FAILED" -> "Run outcome could not be archived";
            case "STATIC_SOURCE_DRIFT" -> "Source changed relative to the analysis context";
            case "STATIC_ANALYSIS_FAILED" -> "Static analysis could not be completed";
            case "TARGET_TEST_NOT_FOUND" -> "Target test was not found in the current source";
            case "STATIC_ARCHIVE_FAILED" -> "Static analysis artifact could not be archived";
            case "PLAN_COMPILATION_FAILED" -> "Collection plan could not be compiled";
            case "PLAN_EVIDENCE_NOT_FOUND" ->
                    "Plan references an Evidence ID that is not available in the current Case";
            case "PLAN_ARCHIVE_FAILED" -> "Collection plan could not be archived";
            case "JDWP_PLAN_COMPILATION_FAILED" -> "JDWP collection plan could not be compiled";
            case "JDWP_PLAN_ARCHIVE_FAILED" -> "JDWP collection plan could not be archived";
            case "JDWP_TOOL_NOT_CONFIGURED" -> "JDWP Collector is not configured";
            case "JDWP_ATTACH_FAILED" -> "JDWP Collector could not attach to the target test";
            case "JDWP_MANIFEST_INVALID" -> "JDWP Collector output is invalid";
            case "JDWP_ARCHIVE_FAILED" -> "JDWP collection artifacts could not be archived";
            default -> "Workspace operation failed";
        };
    }

    private static String publicDomainMessage(CaseRunException failure) {
        if (("PLAN_COMPILATION_FAILED".equals(failure.code())
                || "JDWP_PLAN_COMPILATION_FAILED".equals(failure.code()))
                && failure.getCause() instanceof PlanCompilationException planFailure) {
            return planCompilationMessage(failure.code(), planFailure);
        }
        return safeDomainMessage(failure.code());
    }

    private static String planCompilationMessage(String code, PlanCompilationException failure) {
        String base = safeDomainMessage(code);
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            return base;
        }
        String singleLine = detail.replaceAll("\\s+", " ").strip();
        if (singleLine.length() > 512) {
            singleLine = singleLine.substring(0, 509) + "...";
        }
        return base + ": " + singleLine;
    }
}

@FunctionalInterface
interface CommandExecution {
    Object execute(CliCommand command);
}
