package org.example.algorithmdebug.codepath;

/** 由 Agent-owned Launcher stdout 读取的结构化完成事实。 */
public record CodePathLauncherSummary(
        String outcome,
        long testsFound,
        long testsSucceeded,
        long testsAborted,
        long testsFailed,
        long eventsWritten,
        long bytesWritten,
        String limit,
        String detail) {

    /** 校验协议枚举、计数和有界详情。 */
    public CodePathLauncherSummary {
        if (!("TARGET_SUCCEEDED".equals(outcome) || "TARGET_FAILED".equals(outcome)
                || "TOOL_FAILED".equals(outcome))
                || !("NONE".equals(limit) || "OUTPUT_BYTES".equals(limit) || "EVENTS".equals(limit))
                || testsFound < 0 || testsSucceeded < 0 || testsAborted < 0 || testsFailed < 0
                || testsSucceeded + testsAborted + testsFailed > testsFound
                || eventsWritten < 0 || bytesWritten < 0 || detail == null || detail.length() > 2_048) {
            throw new IllegalArgumentException("CodePath Launcher Summary is invalid");
        }
    }

    /** @return Launcher 是否命中 Raw 硬预算。 */
    public boolean truncated() {
        return !"NONE".equals(limit);
    }
}
