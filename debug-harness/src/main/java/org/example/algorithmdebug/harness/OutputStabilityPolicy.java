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
            throw new IllegalArgumentException("pollInterval 必须在 1 毫秒到 5 秒之间");
        }
        if (timeout == null || timeout.compareTo(pollInterval) <= 0
                || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("timeout 必须大于 pollInterval 且不超过 60 秒");
        }
        if (requiredStableObservations < 2 || requiredStableObservations > 5) {
            throw new IllegalArgumentException("requiredStableObservations 必须在 2 到 5 之间");
        }
    }

    /** @return 普通本地结果文件的默认稳定策略 */
    public static OutputStabilityPolicy defaults() {
        return new OutputStabilityPolicy(Duration.ofMillis(100), Duration.ofSeconds(2), 2);
    }
}
