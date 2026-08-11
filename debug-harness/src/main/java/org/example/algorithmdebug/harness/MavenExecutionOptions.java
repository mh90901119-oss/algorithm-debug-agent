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
            throw new IllegalArgumentException("mavenExecutable 必须是已存在的普通文件: " + mavenExecutable);
        }
        stdoutLog = requireAbsolute(stdoutLog, "stdoutLog");
        stderrLog = requireAbsolute(stderrLog, "stderrLog");
        if (stdoutLog.equals(stderrLog)) {
            throw new IllegalArgumentException("stdoutLog 与 stderrLog 必须是不同文件");
        }
        if (processLimits == null) {
            throw new IllegalArgumentException("processLimits 不能为空");
        }
    }

    private static Path requireAbsolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(field + " 必须是绝对路径");
        }
        return value.normalize();
    }
}
