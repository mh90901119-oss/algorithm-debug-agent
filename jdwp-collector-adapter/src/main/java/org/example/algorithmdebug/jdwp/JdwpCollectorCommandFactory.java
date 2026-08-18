package org.example.algorithmdebug.jdwp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 按锁定 Collector CLI 契约生成不经过 Shell 的精确 argv。 */
public final class JdwpCollectorCommandFactory {
    /**
     * @param javaExecutable Java 21 可执行文件
     * @param collectorJar 已通过 SHA-256 校验的锁定 Collector JAR
     * @param collectorPlan 已归档且端口一致的 Collector Plan
     * @param outputDirectory 本次 create-new Collector 输出目录
     * @param port 已写入 Collector Plan 的 loopback 端口
     * @return 固定 host 为 127.0.0.1 的 Collector argv
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
                "--plan", plan.toString(), "--host", "127.0.0.1", "--port", Integer.toString(port),
                "--output", output.toString());
    }

    private static Path requireFile(Path value, String field) {
        Path path = requireAbsolute(value, field);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(field + " 必须是已存在的普通文件: " + path);
        }
        return path;
    }

    private static Path requireAbsolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(field + " 必须是绝对路径");
        }
        return value.normalize();
    }
}
