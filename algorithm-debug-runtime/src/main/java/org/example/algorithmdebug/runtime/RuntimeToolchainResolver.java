package org.example.algorithmdebug.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** 从受限环境快照解析共享 Runtime 工具链，不读取 CLI 全局状态。 */
public final class RuntimeToolchainResolver {
    private static final String TARGET_JAVA_HOME = "ADA_TARGET_JAVA_HOME";
    private static final String MAVEN_EXECUTABLE = "ADA_MAVEN_EXECUTABLE";

    private final Map<String, String> environment;
    private final boolean windows;

    /** @param environment 不可变环境快照 @param windows 是否使用 Windows 可执行文件名 */
    public RuntimeToolchainResolver(Map<String, String> environment, boolean windows) {
        if (environment == null || environment.entrySet().stream().anyMatch(value ->
                value.getKey() == null || value.getValue() == null)) {
            throw new IllegalArgumentException("environment must not contain null values");
        }
        this.environment = Map.copyOf(environment);
        this.windows = windows;
    }

    /** 解析 Agent Java、目标 Java 和可选 Maven。 */
    public RuntimeToolchain resolve(Path agentJavaExecutable) {
        String targetHome = value(TARGET_JAVA_HOME);
        Path targetJava = targetHome == null || targetHome.isBlank()
                ? agentJavaExecutable
                : Path.of(targetHome, "bin", windows ? "java.exe" : "java");
        String maven = value(MAVEN_EXECUTABLE);
        return new RuntimeToolchain(
                agentJavaExecutable,
                targetJava,
                maven == null || maven.isBlank()
                        ? Optional.empty() : Optional.of(Path.of(maven)));
    }

    private String value(String name) {
        String direct = environment.get(name);
        if (direct != null || !windows) {
            return direct;
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
