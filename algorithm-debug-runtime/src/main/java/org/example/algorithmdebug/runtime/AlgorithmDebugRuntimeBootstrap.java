package org.example.algorithmdebug.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionRequest;
import org.example.algorithmdebug.core.ControlPlaneServices;
import org.example.algorithmdebug.core.coordination.AnalysisActionRegistry;
import org.example.algorithmdebug.core.coordination.AnalysisCoordinator;
import org.example.algorithmdebug.core.coordination.CoreActionHandlers;
import org.example.algorithmdebug.core.coordination.OperationIdempotencyService;
import org.example.algorithmdebug.core.coordination.WorkspaceExecutionLockManager;

/**
 * 共享算法调试 Runtime 的唯一组合根。
 *
 * <p>该类只装配依赖，不创建 Workspace、Project 或 Analysis。CLI、MCP 及其他宿主必须复用此
 * Runtime，不能各自再建 Coordinator 或绕过动作策略。</p>
 */
public final class AlgorithmDebugRuntimeBootstrap {
    private static final String MAVEN_CAPABILITY = "maven";
    private static final String MAVEN_AVAILABLE = "MAVEN_EXECUTABLE_CONFIGURED";
    private static final String MAVEN_UNAVAILABLE = "MAVEN_EXECUTABLE_NOT_CONFIGURED";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String DECISION_ID_PREFIX = "decision-";
    private static final String BOOTSTRAP_FAILURE = "RUNTIME_BOOTSTRAP_FAILED";

    private final AdapterSource adapterSource;
    private final ManagedResourceSource managedResourceSource;

    /** 使用 Java SPI 发现目标项目 Adapter，且不接管额外宿主资源。 */
    public AlgorithmDebugRuntimeBootstrap() {
        this(
                () -> ServiceLoader.load(TargetProjectAdapter.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList(),
                List::of);
    }

    AlgorithmDebugRuntimeBootstrap(AdapterSource adapterSource) {
        this(adapterSource, List::of);
    }

    AlgorithmDebugRuntimeBootstrap(
            AdapterSource adapterSource, ManagedResourceSource managedResourceSource) {
        if (adapterSource == null || managedResourceSource == null) {
            throw new IllegalArgumentException("Bootstrap sources must not be null");
        }
        this.adapterSource = adapterSource;
        this.managedResourceSource = managedResourceSource;
    }

    /**
     * 从冻结请求装配一个共享 Runtime。
     *
     * @param request 可信环境、时钟、平台和日志端口
     * @return 尚未执行任何业务写入的运行时
     */
    public AlgorithmDebugRuntime bootstrap(RuntimeBootstrapRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        List<AutoCloseable> resources = List.of();
        try {
            RuntimeToolchain toolchain = new RuntimeToolchainResolver(
                    request.environment(), request.windows())
                    .resolve(request.agentJavaExecutable());
            CodePathRuntimeFactory.ConfiguredCodePath codePath =
                    new CodePathRuntimeFactory().configure(toolchain, request.environment());
            JdwpRuntimeFactory.ConfiguredJdwp jdwp =
                    new JdwpRuntimeFactory().configure(toolchain, request.environment());
            Map<String, RuntimeCapabilityStatus> capabilities = capabilities(
                    toolchain, codePath.status(), jdwp.status());

            List<TargetProjectAdapter> adapters = immutableAdapters(adapterSource.load());
            resources = immutableResources(managedResourceSource.load());
            ControlPlaneServices baseServices = ControlPlaneServices.create(
                    request.clock(), request.javaFeatureSupplier(), request.environment(),
                    request.pathSeparator(), request.windows(), adapters,
                    toolchain.mavenExecutable(), toolchain.targetJavaExecutable(),
                    codePath.collector(), codePath.classpathResolver(),
                    jdwp.tool(), jdwp.executor(), jdwp.ports(),
                    codePath.doctorProbe(), jdwp.doctorProbe(), request.executionLog());

            BoundedDocumentMapper mapper = new BoundedDocumentMapper();
            RuntimeAnalysisArchiveRouter router = new RuntimeAnalysisArchiveRouter(
                    request.workspaceRoot(), mapper, new AtomicDocumentWriter(),
                    request.clock(), capabilities);
            CoreActionHandlers.ServiceBindings bindings = new CoreActionHandlers.ServiceBindings(
                    request.workspaceId(), request.workspaceRoot(),
                    baseServices.cases(), baseServices.algorithmInputs(), baseServices.runs(),
                    baseServices.staticAnalysis(), baseServices.collections(),
                    baseServices.jdwpCollections(), router, router, router);
            AnalysisActionRegistry registry = CoreActionHandlers.createRegistry(
                    router, CoreActionHandlers.fromServices(bindings));
            AnalysisCoordinator coordinator = new AnalysisCoordinator(
                    registry,
                    router,
                    new OperationIdempotencyService(router),
                    WorkspaceExecutionLockManager.lazyWorkspace(
                            WorkspaceLayout.of(request.workspaceRoot()).projectsRoot()),
                    router,
                    actionRequest -> requestHash(mapper, actionRequest),
                    request.clock(),
                    () -> DECISION_ID_PREFIX + UUID.randomUUID());
            ControlPlaneServices services = baseServices.withCoordinator(coordinator);
            return new AlgorithmDebugRuntime(
                    services, coordinator, toolchain, capabilities, resources);
        } catch (RuntimeException failure) {
            closeAfterFailedBootstrap(resources, failure);
            throw failure;
        } catch (Exception failure) {
            closeAfterFailedBootstrap(resources, failure);
            throw new RuntimeBootstrapException(
                    BOOTSTRAP_FAILURE, "Runtime bootstrap failed", failure);
        }
    }

    private static Map<String, RuntimeCapabilityStatus> capabilities(
            RuntimeToolchain toolchain,
            RuntimeCapabilityStatus codePath,
            RuntimeCapabilityStatus jdwp) {
        RuntimeCapabilityStatus maven = toolchain.mavenExecutable().isPresent()
                ? RuntimeCapabilityStatus.available(MAVEN_CAPABILITY, MAVEN_AVAILABLE)
                : RuntimeCapabilityStatus.unavailable(
                        MAVEN_CAPABILITY, MAVEN_UNAVAILABLE,
                        "Maven executable is not configured");
        Map<String, RuntimeCapabilityStatus> statuses = new LinkedHashMap<>();
        statuses.put(MAVEN_CAPABILITY, maven);
        statuses.put(CodePathRuntimeFactory.CAPABILITY, codePath);
        statuses.put(JdwpRuntimeFactory.CAPABILITY, jdwp);
        return Map.copyOf(statuses);
    }

    private static List<TargetProjectAdapter> immutableAdapters(
            List<TargetProjectAdapter> adapters) {
        if (adapters == null || adapters.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Adapter source returned invalid values");
        }
        return List.copyOf(adapters);
    }

    private static List<AutoCloseable> immutableResources(List<AutoCloseable> resources) {
        if (resources == null || resources.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Managed resource source returned invalid values");
        }
        return List.copyOf(resources);
    }

    private static String requestHash(
            BoundedDocumentMapper mapper, AnalysisActionRequest<?> request) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            digest.update(mapper.writeJson(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(HASH_ALGORITHM + " is unavailable", impossible);
        }
    }

    private static void closeAfterFailedBootstrap(
            List<AutoCloseable> resources, Throwable bootstrapFailure) {
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception closeFailure) {
                bootstrapFailure.addSuppressed(closeFailure);
            }
        }
    }

    @FunctionalInterface
    interface AdapterSource {
        List<TargetProjectAdapter> load();
    }

    @FunctionalInterface
    interface ManagedResourceSource {
        List<AutoCloseable> load();
    }
}
