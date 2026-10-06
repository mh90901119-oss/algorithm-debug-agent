package org.example.algorithmdebug.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import org.example.algorithmdebug.codepath.CodePathProcessCollector;
import org.example.algorithmdebug.codepath.CodePathToolConfiguration;
import org.example.algorithmdebug.codepath.MavenTestClasspathResolver;
import org.example.algorithmdebug.contracts.DoctorCheck;
import org.example.algorithmdebug.contracts.DoctorStatus;
import org.example.algorithmdebug.core.ToolDoctorProbe;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.methodpath.TargetClasspathResolver;

/** 只负责 CodePath Collector、Classpath Resolver 和 Doctor Probe 的具体装配。 */
public final class CodePathRuntimeFactory {
    static final String CAPABILITY = "codepath";
    private static final String ENV_LAUNCHER_JAR = "ADA_CODEPATH_LAUNCHER_JAR";
    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final String MAIN_CLASS =
            "org.example.algorithmdebug.codepath.launcher.ExternalJUnitTraceLauncher";

    /** 根据 Runtime 工具链与受限环境快照装配 CodePath。 */
    public ConfiguredCodePath configure(
            RuntimeToolchain toolchain, Map<String, String> environment) {
        if (toolchain == null || environment == null) {
            throw new IllegalArgumentException("toolchain and environment must not be null");
        }
        TargetClasspathResolver classpathResolver = new MavenTestClasspathResolver();
        String configured = environment.get(ENV_LAUNCHER_JAR);
        if (configured == null || configured.isBlank()) {
            RuntimeCapabilityStatus status = RuntimeCapabilityStatus.unavailable(
                    CAPABILITY, "CODEPATH_TOOL_NOT_CONFIGURED",
                    "CodePath launcher is not configured");
            return new ConfiguredCodePath(
                    status, unavailable(status), classpathResolver, doctor(status));
        }
        Path launcher = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(launcher)) {
            RuntimeCapabilityStatus status = RuntimeCapabilityStatus.unavailable(
                    CAPABILITY, "CODEPATH_TOOL_MISSING",
                    "Configured CodePath launcher is unavailable");
            return new ConfiguredCodePath(
                    status, unavailable(status), classpathResolver, doctor(status));
        }
        CodePathToolConfiguration configuration = new CodePathToolConfiguration(
                toolchain.targetJavaExecutable(), launcher, VERSION, MAIN_CLASS);
        RuntimeCapabilityStatus status = RuntimeCapabilityStatus.available(
                CAPABILITY, "CODEPATH_TOOL_OK");
        return new ConfiguredCodePath(
                status, new CodePathProcessCollector(configuration), classpathResolver,
                doctor(status));
    }

    /** CodePath 运行组件与显式能力状态。 */
    public record ConfiguredCodePath(
            RuntimeCapabilityStatus status,
            MethodPathCollector collector,
            TargetClasspathResolver classpathResolver,
            ToolDoctorProbe doctorProbe) {
        /** 拒绝半装配结果。 */
        public ConfiguredCodePath {
            if (status == null || collector == null || classpathResolver == null
                    || doctorProbe == null) {
                throw new IllegalArgumentException("ConfiguredCodePath fields must not be null");
            }
        }
    }

    private static MethodPathCollector unavailable(RuntimeCapabilityStatus status) {
        return request -> {
            throw new MethodPathCollectionException(status.code(), status.message(), null);
        };
    }

    private static ToolDoctorProbe doctor(RuntimeCapabilityStatus status) {
        return () -> new DoctorCheck(
                CAPABILITY,
                status.available() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                status.code(), status.message());
    }
}
