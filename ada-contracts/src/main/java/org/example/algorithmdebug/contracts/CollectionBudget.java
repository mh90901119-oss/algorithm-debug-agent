package org.example.algorithmdebug.contracts;

/** CodePath 动态采集的事件、字节与时间硬预算。 */
public record CollectionBudget(long maxEvents, long maxBytes, long timeoutMillis) {
    /** 校验预算不超过 Agent 安全硬上限。 */
    public CollectionBudget {
        if (maxEvents < 1 || maxEvents > 1_000_000
                || maxBytes < 1 || maxBytes > 50L * 1024 * 1024
                || timeoutMillis < 1 || timeoutMillis > 20 * 60_000L) {
            throw new IllegalArgumentException("CollectionBudget is outside the safe range");
        }
    }

    /** 返回日常诊断默认预算。 */
    public static CollectionBudget defaults() {
        return new CollectionBudget(100_000, 16L * 1024 * 1024, 5 * 60_000L);
    }
}
