package org.example.algorithmdebug.core;

import java.nio.file.Path;

/** JDWP Collector 的本机 JAR 位置与声明版本。 */
public record JdwpToolConfiguration(Path collectorJar, String version) {
    /** 校验配置形状；文件存在性在 Doctor 和每次执行请求中检查。 */
    public JdwpToolConfiguration {
        if (collectorJar == null) {
            throw new IllegalArgumentException("collectorJar must not be null");
        }
        collectorJar = collectorJar.toAbsolutePath().normalize();
        if (version == null || version.isBlank() || version.length() > 256) {
            throw new IllegalArgumentException("version is invalid");
        }
    }
}
