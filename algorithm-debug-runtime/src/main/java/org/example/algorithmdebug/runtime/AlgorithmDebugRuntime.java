package org.example.algorithmdebug.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.example.algorithmdebug.core.ControlPlaneServices;
import org.example.algorithmdebug.core.coordination.AnalysisCoordinator;

/**
 * 一次共享算法调试运行时的不可变句柄。
 *
 * <p>该对象持有唯一 Coordinator、底层应用服务、已解析工具链和能力快照。宿主关闭运行时后，
 * 所有由组合根接管的资源按逆序且仅关闭一次。</p>
 */
public final class AlgorithmDebugRuntime implements AutoCloseable {
    private static final String CLOSE_FAILURE_CODE = "RUNTIME_CLOSE_FAILED";

    private final ControlPlaneServices services;
    private final AnalysisCoordinator coordinator;
    private final RuntimeToolchain toolchain;
    private final Map<String, RuntimeCapabilityStatus> capabilities;
    private final List<AutoCloseable> managedResources;
    private final AtomicBoolean closed = new AtomicBoolean();

    AlgorithmDebugRuntime(
            ControlPlaneServices services,
            AnalysisCoordinator coordinator,
            RuntimeToolchain toolchain,
            Map<String, RuntimeCapabilityStatus> capabilities,
            List<AutoCloseable> managedResources) {
        if (services == null || coordinator == null || toolchain == null
                || capabilities == null || managedResources == null
                || capabilities.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)
                || managedResources.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Runtime fields must not contain null values");
        }
        this.services = services;
        this.coordinator = coordinator;
        this.toolchain = toolchain;
        this.capabilities = Map.copyOf(capabilities);
        this.managedResources = List.copyOf(managedResources);
    }

    /** @return 已绑定唯一 Coordinator 的应用服务集合 */
    public ControlPlaneServices services() {
        return services;
    }

    /** @return 所有模型可调用分析动作的唯一协调入口 */
    public AnalysisCoordinator coordinator() {
        return coordinator;
    }

    /** @return Agent Java、目标 Java 和 Maven 的冻结解析结果 */
    public RuntimeToolchain toolchain() {
        return toolchain;
    }

    /**
     * 查询一项显式运行时能力。
     *
     * @param name 能力名称
     * @return 能力状态
     * @throws IllegalArgumentException 能力未由组合根注册
     */
    public RuntimeCapabilityStatus capability(String name) {
        if (name == null || name.isBlank() || !name.equals(name.strip())) {
            throw new IllegalArgumentException("Capability name is invalid");
        }
        RuntimeCapabilityStatus status = capabilities.get(name);
        if (status == null) {
            throw new IllegalArgumentException("Capability is not registered: " + name);
        }
        return status;
    }

    /** @return 不可变的运行时能力快照 */
    public Map<String, RuntimeCapabilityStatus> capabilities() {
        return capabilities;
    }

    /** @return 运行时是否已经执行过关闭流程 */
    public boolean closed() {
        return closed.get();
    }

    /**
     * 按注册逆序幂等关闭资源；多个关闭异常会保留为 suppressed cause。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Throwable> failures = new ArrayList<>();
        for (int index = managedResources.size() - 1; index >= 0; index--) {
            try {
                managedResources.get(index).close();
            } catch (Exception failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            RuntimeBootstrapException combined = new RuntimeBootstrapException(
                    CLOSE_FAILURE_CODE, "One or more runtime resources could not be closed",
                    failures.getFirst());
            failures.stream().skip(1).forEach(combined::addSuppressed);
            throw combined;
        }
    }
}
