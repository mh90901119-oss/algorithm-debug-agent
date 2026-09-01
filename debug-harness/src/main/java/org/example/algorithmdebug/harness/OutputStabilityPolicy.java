package org.example.algorithmdebug.harness;

import java.time.Duration;

/**
 * 调度结果文件元数据稳定轮询策略。
 *
 * @param pollInterval 两次快照之间的等待
 * @param timeout 总轮询预算
 * @param requiredStableObservations 连续相同快照次数
 */
public record OutputStabilityPolicy(
        Duration pollInterval,
        Duration timeout,
        int requiredStableObservations) {

    /** 校验轮询不会无界或退化为忙等。 */
    public OutputStabilityPolicy {
        if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(1)) < 0
                || pollInterval.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("pollInterval must be between 1 millisecond and 5 seconds");
        }
        if (timeout == null || timeout.compareTo(pollInterval) <= 0
                || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("timeout must be greater than pollInterval and no more than 60 seconds");
        }
        if (requiredStableObservations < 2 || requiredStableObservations > 5) {
            throw new IllegalArgumentException("requiredStableObservations must be between 2 and 5");
        }
    }

    /** @return 普通本地结果文件的默认稳定策略 */
    public static OutputStabilityPolicy defaults() {
        return new OutputStabilityPolicy(Duration.ofMillis(100), Duration.ofSeconds(2), 2);
    }
}
