package org.example.algorithmdebug.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.ToolResponse;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;
import org.example.algorithmdebug.core.ControlPlaneServices;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.plan.JdwpPlanRequest;

/**
 * CLI 命令执行边界。
 *
 * <p>Workspace、Project 和 Doctor 是宿主管理命令，直接进入 Control Plane；其余分析命令
 * 必须先映射成 typed ActionRequest，再且仅再调用一次共享 Coordinator。</p>
 */
public final class CliCommandExecutor {
    private static final int MAX_REQUEST_BYTES = 65_536;

    private final ManagementExecution management;
    private final ActionRequestMapping requests;
    private final CoordinatedExecution coordinator;
    private final CliCoordinatedResultAdapter resultAdapter;

    /** 使用共享 Runtime 暴露的服务和唯一 Coordinator 创建 CLI 执行器。 */
    CliCommandExecutor(
            ControlPlaneServices services,
            CliActionRequestMapper requestMapper) {
        this(
                managementPort(services),
                mappingPort(requestMapper),
                coordinatorPort(services),
                new CliCoordinatedResultAdapter());
    }

    /** 供契约测试注入窄端口，验证分析命令不存在旁路。 */
    CliCommandExecutor(
            ManagementExecution management,
            ActionRequestMapping requests,
            CoordinatedExecution coordinator,
            CliCoordinatedResultAdapter resultAdapter) {
        if (management == null || requests == null || coordinator == null
                || resultAdapter == null) {
            throw new IllegalArgumentException("CLI execution dependencies must not be null");
        }
        this.management = management;
        this.requests = requests;
        this.coordinator = coordinator;
        this.resultAdapter = resultAdapter;
    }

    /**
     * 执行一个命令，并始终返回 ToolResponse 2.0。
     *
     * @param command 已严格解析的命令
     * @return 稳定 CLI 协议响应
     */
    public ToolResponse<?> execute(CliCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (isManagement(command)) {
            return ToolResponse.success(management.execute(command), List.of());
        }
        AnalysisActionRequest<?> request = requests.map(command);
        return resultAdapter.adapt(coordinator.execute(request));
    }

    private static boolean isManagement(CliCommand command) {
        return command instanceof CliCommand.WorkspaceInit
                || command instanceof CliCommand.ProjectRegister
                || command instanceof CliCommand.Doctor;
    }

    private static ManagementExecution managementPort(ControlPlaneServices services) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        return command -> executeManagement(services, command);
    }

    private static ActionRequestMapping mappingPort(CliActionRequestMapper requestMapper) {
        if (requestMapper == null) {
            throw new IllegalArgumentException("requestMapper must not be null");
        }
        return requestMapper::map;
    }

    private static CoordinatedExecution coordinatorPort(ControlPlaneServices services) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        return services.coordinator()::execute;
    }

    private static Object executeManagement(
            ControlPlaneServices services, CliCommand command) {
        if (command instanceof CliCommand.WorkspaceInit value) {
            return services.workspace().initialize(value.root());
        }
        if (command instanceof CliCommand.ProjectRegister value) {
            return services.project().register(
                    value.workspace(), value.module(), value.projectId(),
                    value.resultJsonDirectory());
        }
        if (command instanceof CliCommand.Doctor value) {
            return services.doctor().diagnose(
                    value.workspace(), value.module(), Optional.empty());
        }
        throw new IllegalArgumentException("Command is not a management command");
    }

    /** 严格读取 64 KiB 内的 CodePath 计划请求 JSON。 */
    static CodePathPlanRequest readPlanRequest(Path path) {
        byte[] bytes = readBoundedFile(path, "request-file");
        String json = decodeUtf8(bytes, "request-file");
        try {
            return strictRequestMapper().readValue(json, CodePathPlanRequest.class);
        } catch (IOException | RuntimeException failure) {
            throw new CliInputException(
                    "request-file is not valid CodePathPlanRequest JSON", failure);
        }
    }

    /** 严格读取 64 KiB 内且不允许未知字段的 JDWP 计划请求 JSON。 */
    static JdwpPlanRequest readJdwpPlanRequest(Path path) {
        byte[] bytes = readBoundedFile(path, "request-file");
        String json = decodeUtf8(bytes, "request-file");
        try {
            return strictRequestMapper().readValue(json, JdwpPlanRequest.class);
        } catch (IOException | RuntimeException failure) {
            throw new CliInputException(
                    "request-file is not valid JdwpPlanRequest JSON", failure);
        }
    }

    /** 严格读取 64 KiB 内且不允许未知字段的动态证据查询请求 JSON。 */
    static org.example.algorithmdebug.contracts.EvidenceQueryRequest readEvidenceQueryRequest(
            Path path) {
        byte[] bytes = readBoundedFile(path, "request-file");
        String json = decodeUtf8(bytes, "request-file");
        try {
            return strictRequestMapper().readValue(
                    json, org.example.algorithmdebug.contracts.EvidenceQueryRequest.class);
        } catch (IOException | RuntimeException failure) {
            throw new CliInputException(
                    "request-file is not valid EvidenceQueryRequest JSON", failure);
        }
    }

    static ObjectMapper strictRequestMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory)
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static String decodeUtf8(byte[] bytes, String label) {
        if (bytes == null) {
            throw new CliInputException(label + " content must not be null");
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        } catch (CharacterCodingException failure) {
            throw new CliInputException(label + " is not valid UTF-8", failure);
        }
    }

    static byte[] readBoundedFile(Path path, String label) {
        return readBoundedFile(path, label, MAX_REQUEST_BYTES);
    }

    private static byte[] readBoundedFile(Path path, String label, int maximumBytes) {
        if (path == null) {
            throw new CliInputException(label + " must not be null");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new CliInputException(label + " does not exist or is not a regular file");
        }
        try (java.io.InputStream input = Files.newInputStream(normalized)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new CliInputException(label + " exceeds the read budget");
            }
            return bytes;
        } catch (IOException | SecurityException failure) {
            throw new CliInputException("Unable to read " + label, failure);
        }
    }
}

@FunctionalInterface
interface ManagementExecution {
    Object execute(CliCommand command);
}

@FunctionalInterface
interface ActionRequestMapping {
    AnalysisActionRequest<?> map(CliCommand command);
}

@FunctionalInterface
interface CoordinatedExecution {
    CoordinatedToolResult<?> execute(AnalysisActionRequest<?> request);
}
