package org.example.algorithmdebug.harness;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一次 Maven 运行所需的机器相关选项，不进入业务 Adapter。
 *
 * @param mavenExecutable Maven 可执行文件绝对路径
 * @param stdoutLog stdout 不可覆盖归档路径
 * @param stderrLog stderr 不可覆盖归档路径
 * @param processLimits 日志和进程终止预算
 */
public record MavenExecutionOptions(
        Path mavenExecutable,
        Path stdoutLog,
        Path stderrLog,
        ProcessLimits processLimits) {

    /** 规范化路径并拒绝不存在的 Maven executable。 */
    public MavenExecutionOptions {
        mavenExecutable = requireAbsolute(mavenExecutable, "mavenExecutable");
        if (!Files.isRegularFile(mavenExecutable)) {
            throw new IllegalArgumentException("mavenExecutable must be an existing regular file: " + mavenExecutable);
        }
        stdoutLog = requireAbsolute(stdoutLog, "stdoutLog");
        stderrLog = requireAbsolute(stderrLog, "stderrLog");
        if (stdoutLog.equals(stderrLog)) {
            throw new IllegalArgumentException("stdoutLog and stderrLog must be different files");
        }
        if (processLimits == null) {
            throw new IllegalArgumentException("processLimits must not be null");
        }
    }

    private static Path requireAbsolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be an absolute path");
        }
        return value.normalize();
    }
}
