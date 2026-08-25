package org.example.algorithmdebug.jdwp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 根据确定性 Collector CLI 契约生成不经过 Shell 的精确 argv。 */
public final class JdwpCollectorCommandFactory {
    /**
     * @param javaExecutable Java 21 可执行文件
     * @param collectorJar 仓库内置 Collector JAR
     * @param collectorPlan 已归档且包含本次 endpoint 的 Collector Plan
     * @param outputDirectory 本次 create-new Collector 输出目录
     * @param port 已写入 Collector Plan 的 loopback 端口，仅用于边界校验
     * @return endpoint 只从已归档 Plan 读取的 Collector argv
     */
    public List<String> create(
            Path javaExecutable,
            Path collectorJar,
            Path collectorPlan,
            Path outputDirectory,
            int port) {
        Path java = requireFile(javaExecutable, "javaExecutable");
        Path jar = requireFile(collectorJar, "collectorJar");
        Path plan = requireFile(collectorPlan, "collectorPlan");
        Path output = requireAbsolute(outputDirectory, "outputDirectory");
        JdwpTargetCommandFactory.requirePort(port);
        return List.of(
                java.toString(), "--add-modules", "jdk.jdi", "-jar", jar.toString(), "collect",
                "--plan", plan.toString(), "--output", output.toString());
    }

    private static Path requireFile(Path value, String field) {
        Path path = requireAbsolute(value, field);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(field + " must be an existing regular file: " + path);
        }
        return path;
    }

    private static Path requireAbsolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be an absolute path");
        }
        return value.normalize();
    }
}
