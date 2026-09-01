package org.example.algorithmdebug.harness;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * 一次目标 Maven/JUnit 子进程的不可变结构化结果。
 *
 * @param completion 完成分类
 * @param exitCode 子进程退出码；超时时可以缺失
 * @param startedAt 开始墙钟时间
 * @param finishedAt 结束墙钟时间
 * @param elapsed 单调计时得到的耗时
 * @param rootProcessId Maven 根进程 ID
 * @param stdout stdout 归档摘要
 * @param stderr stderr 归档摘要
 * @param termination 进程树清理报告
 */
public record RunResult(
        RunCompletion completion,
        OptionalInt exitCode,
        Instant startedAt,
        Instant finishedAt,
        Duration elapsed,
        long rootProcessId,
        RunLog stdout,
        RunLog stderr,
        TerminationReport termination) {

    /** 校验完成分类、退出码和清理状态的一致性。 */
    public RunResult {
        if (completion == null || exitCode == null || startedAt == null || finishedAt == null
                || elapsed == null || stdout == null || stderr == null || termination == null) {
            throw new IllegalArgumentException("RunResult parameters must not be null");
        }
        if (finishedAt.isBefore(startedAt) || elapsed.isNegative() || rootProcessId <= 0) {
            throw new IllegalArgumentException("RunResult time or PID is invalid");
        }
        switch (completion) {
            case SUCCEEDED -> {
                if (exitCode.isEmpty() || exitCode.getAsInt() != 0 || termination.attempted()) {
                    throw new IllegalArgumentException("SUCCEEDED requires exit code 0 and no cleanup");
                }
            }
            case FAILED -> {
                if (exitCode.isEmpty() || exitCode.getAsInt() == 0 || termination.attempted()) {
                    throw new IllegalArgumentException("FAILED must have a non-zero exit code without cleanup");
                }
            }
            case TIMED_OUT -> {
                if (!termination.attempted()) {
                    throw new IllegalArgumentException("TIMED_OUT must record process-tree cleanup");
                }
            }
        }
    }
}
