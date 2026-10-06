package org.example.algorithmdebug.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import org.example.algorithmdebug.contracts.DoctorCheck;
import org.example.algorithmdebug.contracts.DoctorStatus;
import org.example.algorithmdebug.core.JdwpCollectionExecutor;
import org.example.algorithmdebug.core.JdwpPortProvider;
import org.example.algorithmdebug.core.JdwpToolConfiguration;
import org.example.algorithmdebug.core.ToolDoctorProbe;
import org.example.algorithmdebug.jdwp.JdwpAdapterException;
import org.example.algorithmdebug.jdwp.JdwpCollectionCoordinator;
import org.example.algorithmdebug.jdwp.LoopbackPortAllocator;

/** 只负责 JDWP Collector 配置、执行器、端口和 Doctor Probe 的具体装配。 */
public final class JdwpRuntimeFactory {
    static final String CAPABILITY = "jdwp";
    private static final String ENV_COLLECTOR_JAR = "ADA_JDWP_COLLECTOR_JAR";
    private static final String VERSION = "2.0.0";

    /** 根据 Runtime 工具链与受限环境快照装配 JDWP。 */
    public ConfiguredJdwp configure(
            RuntimeToolchain toolchain, Map<String, String> environment) {
        if (toolchain == null || environment == null) {
            throw new IllegalArgumentException("toolchain and environment must not be null");
        }
        String configured = environment.get(ENV_COLLECTOR_JAR);
        if (configured == null || configured.isBlank()) {
            RuntimeCapabilityStatus status = RuntimeCapabilityStatus.unavailable(
                    CAPABILITY, "JDWP_TOOL_NOT_CONFIGURED",
                    "JDWP Collector is not configured");
            return unavailable(toolchain, status);
        }
        Path collector = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(collector, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(collector)) {
            RuntimeCapabilityStatus status = RuntimeCapabilityStatus.unavailable(
                    CAPABILITY, "JDWP_TOOL_MISSING",
                    "Configured JDWP Collector is unavailable");
            return unavailable(toolchain, status);
        }
        RuntimeCapabilityStatus status = RuntimeCapabilityStatus.available(
                CAPABILITY, "JDWP_TOOL_OK");
        LoopbackPortAllocator ports = new LoopbackPortAllocator();
        return new ConfiguredJdwp(
                status,
                new JdwpToolConfiguration(collector, VERSION),
                new JdwpCollectionCoordinator()::execute,
                ports::allocate,
                doctor(status));
    }

    /** JDWP 运行组件与显式能力状态。 */
    public record ConfiguredJdwp(
            RuntimeCapabilityStatus status,
            JdwpToolConfiguration tool,
            JdwpCollectionExecutor executor,
            JdwpPortProvider ports,
            ToolDoctorProbe doctorProbe) {
        /** 拒绝半装配结果。 */
        public ConfiguredJdwp {
            if (status == null || tool == null || executor == null
                    || ports == null || doctorProbe == null) {
                throw new IllegalArgumentException("ConfiguredJdwp fields must not be null");
            }
        }
    }

    private static ConfiguredJdwp unavailable(
            RuntimeToolchain toolchain, RuntimeCapabilityStatus status) {
        JdwpCollectionExecutor executor = request -> {
            throw new JdwpAdapterException(status.code(), status.message(), null);
        };
        LoopbackPortAllocator ports = new LoopbackPortAllocator();
        return new ConfiguredJdwp(
                status,
                new JdwpToolConfiguration(toolchain.agentJavaExecutable(), "unavailable"),
                executor, ports::allocate, doctor(status));
    }

    private static ToolDoctorProbe doctor(RuntimeCapabilityStatus status) {
        return () -> new DoctorCheck(
                CAPABILITY,
                status.available() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                status.code(), status.message());
    }
}
