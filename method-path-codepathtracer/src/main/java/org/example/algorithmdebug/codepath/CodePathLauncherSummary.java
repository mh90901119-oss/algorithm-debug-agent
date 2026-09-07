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
        String detail,
        String captureMode,
        String depthSemantics,
        int selectedTypeCount,
        int selectedMethodCount,
        boolean scopeFilterEnabled,
        long scopeInvocationsObserved,
        long scopeInvocationsMatched,
        long scopeInvocationsCaptured,
        long scopeInvocationsSkippedByWindow,
        long scopeConditionUnavailableInvocations,
        long conditionProjectionReads,
        long detailProjectionReads,
        java.util.List<String> reasonCodes) {

    /** 校验协议枚举、计数和有界详情。 */
    public CodePathLauncherSummary {
        if (!("TARGET_SUCCEEDED".equals(outcome) || "TARGET_FAILED".equals(outcome)
                || "TOOL_FAILED".equals(outcome))
                || !("NONE".equals(limit) || "OUTPUT_BYTES".equals(limit) || "EVENTS".equals(limit))
                || testsFound < 0 || testsSucceeded < 0 || testsAborted < 0 || testsFailed < 0
                || testsSucceeded + testsAborted + testsFailed > testsFound
                || eventsWritten < 0 || bytesWritten < 0 || detail == null || detail.length() > 2_048
                || selectedTypeCount < 0 || selectedMethodCount < 0
                || scopeInvocationsObserved < 0 || scopeInvocationsMatched < 0
                || scopeInvocationsCaptured < 0 || scopeInvocationsSkippedByWindow < 0
                || scopeConditionUnavailableInvocations < 0
                || scopeInvocationsCaptured + scopeInvocationsSkippedByWindow > scopeInvocationsMatched
                || scopeInvocationsMatched + scopeConditionUnavailableInvocations
                        > scopeInvocationsObserved
                || conditionProjectionReads < 0 || detailProjectionReads < 0
                || (!scopeFilterEnabled && (scopeInvocationsObserved != 0
                        || scopeInvocationsMatched != 0
                        || scopeInvocationsCaptured != 0 || scopeInvocationsSkippedByWindow != 0
                        || scopeConditionUnavailableInvocations != 0))) {
            throw new IllegalArgumentException("CodePath Launcher Summary is invalid");
        }
        captureMode = captureMode == null ? "TRACE" : captureMode;
        depthSemantics = depthSemantics == null ? "SELECTED_METHOD_DEPTH" : depthSemantics;
        reasonCodes = reasonCodes == null ? java.util.List.of() : java.util.List.copyOf(reasonCodes);
        if (!("TRACE".equals(captureMode) || "AGGREGATE".equals(captureMode))
                || !"SELECTED_METHOD_DEPTH".equals(depthSemantics)
                || reasonCodes.size() > 16) {
            throw new IllegalArgumentException("CodePath Launcher capture metadata is invalid");
        }
    }

    /** @return Launcher 是否命中 Raw 硬预算。 */
    public boolean truncated() {
        return !"NONE".equals(limit) || !reasonCodes.isEmpty();
    }
}
