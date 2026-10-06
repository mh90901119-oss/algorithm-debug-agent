package org.example.algorithmdebug.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** Agent JVM、目标算法 JVM 与 Maven 可执行文件的显式分离结果。 */
public record RuntimeToolchain(
        Path agentJavaExecutable,
        Path targetJavaExecutable,
        Optional<Path> mavenExecutable) {

    /** 校验三个路径均为非符号链接普通文件，并冻结为规范绝对路径。 */
    public RuntimeToolchain {
        agentJavaExecutable = requireFile(agentJavaExecutable, "agentJavaExecutable");
        targetJavaExecutable = requireFile(targetJavaExecutable, "targetJavaExecutable");
        if (mavenExecutable == null) {
            throw new IllegalArgumentException("mavenExecutable must not be null");
        }
        mavenExecutable = mavenExecutable.map(value -> requireFile(value, "mavenExecutable"));
    }

    private static Path requireFile(Path path, String field) {
        if (path == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new RuntimeBootstrapException(
                    "RUNTIME_TOOLCHAIN_FILE_MISSING", field + " is unavailable");
        }
        return normalized;
    }
}
