package org.example.algorithmdebug.contracts;

/**
 * 一次 JDWP 目标进程和 Collector 运行的硬预算。
 *
 * @param maxEvents Collector 最多写入事件数
 * @param maxBytes Agent 允许 Raw Trace 达到的最大字节数
 * @param timeoutMillis 目标与 Collector 整体运行超时
 * @param idleTimeoutMillis Collector 无事件等待超时
 */
public record JdwpCollectionBudget(
        int maxEvents,
        long maxBytes,
        long timeoutMillis,
        long idleTimeoutMillis) {

    /** 校验预算与当前 Collector MVP 的保守使用边界。 */
    public JdwpCollectionBudget {
        if (maxEvents < 1 || maxEvents > 1_000
                || maxBytes < 1 || maxBytes > 50L * 1024 * 1024
                || timeoutMillis < 1_000 || timeoutMillis > 20 * 60_000L
                || idleTimeoutMillis < 1_000 || idleTimeoutMillis > timeoutMillis) {
            throw new IllegalArgumentException("JdwpCollectionBudget 超出安全范围");
        }
    }

    /** 返回小规模、stack-only 采集的默认预算。 */
    public static JdwpCollectionBudget defaults() {
        return new JdwpCollectionBudget(100, 16L * 1024 * 1024, 5 * 60_000L, 120_000L);
    }
}
