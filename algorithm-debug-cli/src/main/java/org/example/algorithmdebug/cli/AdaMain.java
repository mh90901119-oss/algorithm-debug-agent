package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.contracts.ToolResponse;
import org.example.algorithmdebug.core.CaseRunException;
import org.example.algorithmdebug.core.ControlPlaneException;
import org.example.algorithmdebug.core.ControlPlaneServices;
import org.example.algorithmdebug.core.MavenExecutableLocator;

import java.io.File;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

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
            responseWriter.write(ToolResponse.success(result, List.of()), stdout);
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
        ControlPlaneServices services = ControlPlaneServices.create(
                Clock.systemUTC(),
                () -> Runtime.version().feature(),
                System.getenv(),
                File.pathSeparator,
                windows,
                adapters,
                mavenLocator.locate(Optional.empty()));
        return new CliCommandExecutor(
                services.workspace(), services.project(), services.doctor(),
                services.cases(), services.runs());
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
            case "CASE_PROJECT_MISMATCH" -> "Case belongs to another project";
            case "CASE_TARGET_TEST_MISMATCH" -> "Case belongs to another target test";
            case "MAVEN_NOT_FOUND" -> "Maven executable is unavailable";
            case "RUN_ARCHIVE_WRITE_FAILED" -> "Run outcome could not be archived";
            default -> "Workspace operation failed";
        };
    }
}

@FunctionalInterface
interface CommandExecution {
    Object execute(CliCommand command);
}
