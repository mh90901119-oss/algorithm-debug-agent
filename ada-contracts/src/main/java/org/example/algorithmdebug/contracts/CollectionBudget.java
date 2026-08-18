package org.example.algorithmdebug.contracts;

/**
 * 动态采集共享的硬预算。
 *
 * @param maxEvents 最多保留事件数
 * @param maxBytes 最多保留字节数
 * @param timeoutMillis 采集进程最长运行时间
 * @param maxCallDepth 最大调用深度
 */
public record CollectionBudget(long maxEvents, long maxBytes, long timeoutMillis, int maxCallDepth) {

    /** 校验预算不超过 Agent 的安全硬上限。 */
    public CollectionBudget {
        if (maxEvents < 1 || maxEvents > 1_000_000
                || maxBytes < 1 || maxBytes > 50L * 1024 * 1024
                || timeoutMillis < 1 || timeoutMillis > 20 * 60_000L
                || maxCallDepth < 1 || maxCallDepth > 10_000) {
            throw new IllegalArgumentException("CollectionBudget 超出安全范围");
        }
    }

    /** 返回 CodePath 日常诊断默认预算。 */
    public static CollectionBudget defaults() {
        return new CollectionBudget(100_000, 16L * 1024 * 1024, 5 * 60_000L, 1_000);
    }
}
