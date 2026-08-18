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

import java.io.File;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.nio.file.Files;

/** Algorithm Debug Agent 的稳定 JSON CLI 入口。 */
public final class AdaMain {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_INVALID_ARGUMENTS = 2;
    private static final int EXIT_DOMAIN_FAILURE = 3;
    private static final int EXIT_INTERNAL_ERROR = 10;

    private final CommandExecution execution;
    private final CliResponseWriter responseWriter;

    AdaMain(CommandExecution execution, CliResponseWriter responseWriter) {
        if (execution == null || responseWriter == null) {
            throw new IllegalArgumentException("execution 和 responseWriter 不能为空");
        }
        this.execution = execution;
        this.responseWriter = responseWriter;
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
            throw new IllegalArgumentException("stdout 和 stderr 不能为空");
        }
        CliCommand command;
        try {
            command = CliArguments.parse(arguments);
        } catch (IllegalArgumentException failure) {
            responseWriter.write(
                    ToolResponse.failure(
                            "CLI_INVALID_ARGUMENTS", "Invalid CLI arguments", List.of()),
                    stdout);
            return EXIT_INVALID_ARGUMENTS;
        }

        try {
            Object result = execution.execute(command);
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
            responseWriter.write(
                    ToolResponse.failure(
                            "CLI_INVALID_ARGUMENTS", "Invalid CLI arguments", List.of()),
                    stdout);
            return EXIT_INVALID_ARGUMENTS;
        } catch (CaseRunException failure) {
            responseWriter.write(
                    ToolResponse.failure(failure.code(), safeDomainMessage(failure.code()), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (ControlPlaneException failure) {
            responseWriter.write(
                    ToolResponse.failure(failure.code(), safeDomainMessage(failure.code()), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (StaticAnalysisException failure) {
            responseWriter.write(
                    ToolResponse.failure(
                            "STATIC_ANALYSIS_FAILED", safeDomainMessage("STATIC_ANALYSIS_FAILED"), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (PlanCompilationException failure) {
            responseWriter.write(
                    ToolResponse.failure(
                            "PLAN_COMPILATION_FAILED", safeDomainMessage("PLAN_COMPILATION_FAILED"), List.of()),
                    stdout);
            return EXIT_DOMAIN_FAILURE;
        } catch (RuntimeException failure) {
            stderr.println("INTERNAL_ERROR");
            responseWriter.write(
                    ToolResponse.failure("INTERNAL_ERROR", "Internal Agent error", List.of()),
                    stdout);
            return EXIT_INTERNAL_ERROR;
        }
    }

    static AdaMain defaultApplication() {
        CliCommandExecutor executor = defaultExecutor();
        return new AdaMain(executor::execute, new CliResponseWriter());
    }

    /** 装配默认 CLI 执行器；保留包级测试缝以验证组合根。 */
    static CliCommandExecutor defaultExecutor() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        List<TargetProjectAdapter<?>> adapters = List.copyOf(ServiceLoader
                .load(TargetProjectAdapter.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(adapter -> (TargetProjectAdapter<?>) adapter)
                .collect(Collectors.toList()));
        MavenExecutableLocator mavenLocator = new MavenExecutableLocator(
                System.getenv(), File.pathSeparator, windows);
        java.nio.file.Path javaExecutable = java.nio.file.Path.of(
                System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
        ConfiguredCodePath codePath = configuredCodePath(javaExecutable);
        ConfiguredJdwp jdwp = configuredJdwp(javaExecutable);
        ControlPlaneServices services = ControlPlaneServices.create(
                Clock.systemUTC(),
                () -> Runtime.version().feature(),
                System.getenv(),
                File.pathSeparator,
                windows,
                adapters,
                mavenLocator.locate(Optional.empty()),
                codePath.collector(),
                new MavenTestClasspathResolver(),
                jdwp.tool(), jdwp.executor(), jdwp.ports(),
                codePath.doctorProbe(), jdwp.doctorProbe());
        return new CliCommandExecutor(
                services.workspace(), services.project(), services.doctor(),
                services.cases(), services.runs(), services.staticAnalysis(), services.collections(),
                services.jdwpCollections());
    }

    private static ConfiguredCodePath configuredCodePath(java.nio.file.Path javaExecutable) {
        String jar = System.getenv("ADA_CODEPATH_LAUNCHER_JAR");
        String sha = System.getenv("ADA_CODEPATH_LAUNCHER_SHA256");
        if (jar == null || jar.isBlank() || sha == null || sha.isBlank()) {
            MethodPathCollector unavailable = request -> {
                throw new org.example.algorithmdebug.methodpath.MethodPathCollectionException(
                        "CODEPATH_TOOL_NOT_CONFIGURED", "CodePath launcher 未配置", null);
            };
            return new ConfiguredCodePath(unavailable, () -> new DoctorCheck(
                    "codepath", DoctorStatus.FAIL, "CODEPATH_TOOL_NOT_CONFIGURED",
                    "CodePath launcher 未配置"));
        }
        CodePathToolConfiguration configuration = new CodePathToolConfiguration(
                javaExecutable, java.nio.file.Path.of(jar), sha, "0.1.0-SNAPSHOT",
                "org.example.algorithmdebug.codepath.launcher.ExternalJUnitTraceLauncher");
        ToolDoctorProbe probe = () -> {
            if (!java.nio.file.Files.isRegularFile(javaExecutable)) {
                return new DoctorCheck(
                        "codepath", DoctorStatus.FAIL, "CODEPATH_JAVA_MISSING",
                        "CodePath Java executable 不可用");
            }
            try {
                configuration.verifyTool();
                return new DoctorCheck(
                        "codepath", DoctorStatus.PASS, "CODEPATH_TOOL_OK",
                        "CodePath launcher 配置和 SHA-256 校验通过");
            } catch (CodePathAdapterException failure) {
                return new DoctorCheck(
                        "codepath", DoctorStatus.FAIL, failure.code(),
                        "CodePath launcher 配置校验失败");
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
                            "JDWP_TOOL_NOT_CONFIGURED", "JDWP Collector 未配置", null); },
                    () -> 51234,
                    () -> new DoctorCheck(
                            "jdwp", DoctorStatus.FAIL, "JDWP_TOOL_NOT_CONFIGURED",
                            "JDWP Collector 未配置"));
        }
        JdwpToolConfiguration tool = new JdwpToolConfiguration(
                java.nio.file.Path.of(jar), "1.0.0");
        ToolDoctorProbe probe = () -> {
            if (!Files.isRegularFile(tool.collectorJar())) {
                return new DoctorCheck(
                        "jdwp", DoctorStatus.FAIL, "JDWP_TOOL_MISSING",
                        "JDWP Collector JAR 不可用");
            }
            return new DoctorCheck(
                    "jdwp", DoctorStatus.PASS, "JDWP_TOOL_OK",
                    "JDWP Collector JAR 路径可用");
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
            case "ANALYSIS_RESULT_IDENTITY_MISMATCH" -> "Analysis result identity does not match the command";
            case "ANALYSIS_RESULT_NOT_FOUND" -> "Analysis result was not found";
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
            case "STATIC_ARCHIVE_FAILED" -> "Static analysis artifact could not be archived";
            case "PLAN_COMPILATION_FAILED" -> "Collection plan could not be compiled";
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
}

@FunctionalInterface
interface CommandExecution {
    Object execute(CliCommand command);
}
