package org.example.algorithmdebug.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** 将 Agent JVM、目标算法 JVM 和显式 Maven 可执行文件分离。 */
record RuntimeToolchain(
        Path agentJavaExecutable,
        Path targetJavaExecutable,
        Optional<Path> mavenExecutable) {

    static RuntimeToolchain resolve(
            Map<String, String> environment,
            Path agentJavaExecutable,
            boolean windows) {
        Path agent = requireFile(agentJavaExecutable, "Agent Java executable");
        String targetHome = value(environment, "ADA_TARGET_JAVA_HOME", windows);
        Path target = targetHome == null || targetHome.isBlank()
                ? agent
                : requireFile(Path.of(targetHome, "bin", windows ? "java.exe" : "java"),
                        "Target Java executable");
        String configuredMaven = value(environment, "ADA_MAVEN_EXECUTABLE", windows);
        Optional<Path> maven = configuredMaven == null || configuredMaven.isBlank()
                ? Optional.empty()
                : Optional.of(requireFile(Path.of(configuredMaven), "Maven executable"));
        return new RuntimeToolchain(agent, target, maven);
    }

    private static String value(Map<String, String> environment, String name, boolean windows) {
        String direct = environment.get(name);
        if (direct != null || !windows) return direct;
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Path requireFile(Path value, String role) {
        Path normalized = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new CliStartupException(
                    "CLI_TOOLCHAIN_FILE_MISSING", role + " is missing: " + normalized);
        }
        return normalized;
    }
}
