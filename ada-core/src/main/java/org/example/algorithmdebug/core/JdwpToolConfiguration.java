package org.example.algorithmdebug.core;

import java.nio.file.Path;

/** 锁定 JDWP Collector 的本机位置、SHA-256 与版本。 */
public record JdwpToolConfiguration(Path collectorJar, String sha256, String version) {
    /** 校验值形状；文件存在性和 Hash 在每次执行前由 Coordinator 复验。 */
    public JdwpToolConfiguration {
        if (collectorJar == null) {
            throw new IllegalArgumentException("collectorJar 不能为空");
        }
        collectorJar = collectorJar.toAbsolutePath().normalize();
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 必须是小写 SHA-256");
        }
        if (version == null || version.isBlank() || version.length() > 256) {
            throw new IllegalArgumentException("version 无效");
        }
    }
}
