package org.example.algorithmdebug.harness;

import java.time.Duration;

/**
 * 外部进程日志与终止阶段的硬预算。
 *
 * @param maximumStdoutBytes stdout 最大归档字节数
 * @param maximumStderrBytes stderr 最大归档字节数
 * @param gracefulTerminationTimeout 正常终止等待时间
 * @param forcedTerminationTimeout 强制终止等待时间
 */
public record ProcessLimits(
        long maximumStdoutBytes,
        long maximumStderrBytes,
        Duration gracefulTerminationTimeout,
        Duration forcedTerminationTimeout) {

    private static final long MAXIMUM_LOG_BYTES = 100L * 1024 * 1024;
    private static final Duration MAXIMUM_TERMINATION_TIMEOUT = Duration.ofSeconds(30);

    /** 校验预算，防止调用方创建无界日志或无界终止等待。 */
    public ProcessLimits {
        requireLogBudget(maximumStdoutBytes, "maximumStdoutBytes");
        requireLogBudget(maximumStderrBytes, "maximumStderrBytes");
        requireTerminationTimeout(gracefulTerminationTimeout, "gracefulTerminationTimeout");
        requireTerminationTimeout(forcedTerminationTimeout, "forcedTerminationTimeout");
    }

    /** @return 适合普通 Maven UT 的默认进程预算 */
    public static ProcessLimits defaults() {
        return new ProcessLimits(
                10L * 1024 * 1024,
                10L * 1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
    }

    private static void requireLogBudget(long value, String field) {
        if (value <= 0 || value > MAXIMUM_LOG_BYTES) {
            throw new IllegalArgumentException(field + " must be between 1 and 100 MiB");
        }
    }

    private static void requireTerminationTimeout(Duration value, String field) {
        if (value == null || value.isNegative() || value.compareTo(MAXIMUM_TERMINATION_TIMEOUT) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 30 seconds");
        }
    }
}
