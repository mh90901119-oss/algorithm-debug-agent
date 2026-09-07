package org.example.algorithmdebug.codepath.launcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/** Launcher 写入 stdout 的唯一结构化完成事实。 */
public record LauncherSummary(
        LauncherOutcome outcome,
        long testsFound,
        long testsSucceeded,
        long testsAborted,
        long testsFailed,
        long eventsWritten,
        long bytesWritten,
        TraceJsonlSink.Limit limit,
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

    /** 父进程定位 Summary 的稳定前缀。 */
    public static final String LINE_PREFIX = "ADA_CODEPATH_SUMMARY=";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 校验计数和有界详情。 */
    public LauncherSummary {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(limit, "limit");
        if (testsFound < 0 || testsSucceeded < 0 || testsAborted < 0 || testsFailed < 0
                || testsSucceeded + testsAborted + testsFailed > testsFound
                || eventsWritten < 0 || bytesWritten < 0
                || selectedTypeCount < 0 || selectedMethodCount < 0
                || scopeInvocationsObserved < 0 || scopeInvocationsMatched < 0
                || scopeInvocationsCaptured < 0 || scopeInvocationsSkippedByWindow < 0
                || scopeConditionUnavailableInvocations < 0
                || scopeInvocationsCaptured + scopeInvocationsSkippedByWindow > scopeInvocationsMatched
                || scopeInvocationsMatched + scopeConditionUnavailableInvocations > scopeInvocationsObserved
                || conditionProjectionReads < 0 || detailProjectionReads < 0) {
            throw new IllegalArgumentException("Launcher Summary counts must not be negative");
        }
        if (!("TRACE".equals(captureMode) || "AGGREGATE".equals(captureMode))
                || !"SELECTED_METHOD_DEPTH".equals(depthSemantics)) {
            throw new IllegalArgumentException("Launcher Summary capture semantics are invalid");
        }
        if (!scopeFilterEnabled && (scopeInvocationsObserved != 0
                || scopeInvocationsMatched != 0
                || scopeInvocationsCaptured != 0 || scopeInvocationsSkippedByWindow != 0
                || scopeConditionUnavailableInvocations != 0)) {
            throw new IllegalArgumentException("Disabled scope filter must have zero counts");
        }
        detail = Objects.requireNonNull(detail, "detail");
        reasonCodes = java.util.List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
        if (reasonCodes.size() > 16 || reasonCodes.stream().anyMatch(
                value -> value == null || value.isBlank() || value.length() > 128)) {
            throw new IllegalArgumentException("Launcher Summary reasonCodes are invalid");
        }
        if (detail.length() > 2_048) {
            throw new IllegalArgumentException("Launcher Summary detail is too long");
        }
    }

    /** 构造未启用运行时范围过滤的兼容 Summary。 */
    public LauncherSummary(
            LauncherOutcome outcome,
            long testsFound,
            long testsSucceeded,
            long testsAborted,
            long testsFailed,
            long eventsWritten,
            long bytesWritten,
            TraceJsonlSink.Limit limit,
            String detail) {
        this(outcome, testsFound, testsSucceeded, testsAborted, testsFailed,
                eventsWritten, bytesWritten, limit, detail,
                "TRACE", "SELECTED_METHOD_DEPTH", 0, 0,
                false, 0, 0, 0, 0, 0, 0, 0, java.util.List.of());
    }

    /** 兼容旧 Scope Summary 测试与读取方。 */
    public LauncherSummary(
            LauncherOutcome outcome,
            long testsFound,
            long testsSucceeded,
            long testsAborted,
            long testsFailed,
            long eventsWritten,
            long bytesWritten,
            TraceJsonlSink.Limit limit,
            String detail,
            boolean scopeFilterEnabled,
            long scopeInvocationsObserved,
            long scopeInvocationsMatched,
            long scopeConditionUnavailableInvocations) {
        this(outcome, testsFound, testsSucceeded, testsAborted, testsFailed,
                eventsWritten, bytesWritten, limit, detail,
                "TRACE", "SELECTED_METHOD_DEPTH", 0, 0,
                scopeFilterEnabled, scopeInvocationsObserved, scopeInvocationsMatched,
                scopeInvocationsMatched, 0, scopeConditionUnavailableInvocations,
                0, 0, java.util.List.of());
    }

    /** @return 目标测试是否失败；不依赖进程退出码猜测。 */
    public boolean targetFailed() {
        return testsFailed > 0 || testsAborted > 0;
    }

    /** @return Raw 是否因为预算截断。 */
    public boolean truncated() {
        return limit != TraceJsonlSink.Limit.NONE;
    }

    /** 序列化为单行、可由父进程确定性定位的 JSON。 */
    public String toStructuredLine() {
        try {
            return LINE_PREFIX + JSON.writeValueAsString(this);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize the Launcher Summary", failure);
        }
    }

    /** 解析父进程捕获到的结构化行。 */
    public static LauncherSummary parseStructuredLine(String line) {
        if (line == null || !line.startsWith(LINE_PREFIX)) {
            throw new IllegalArgumentException("The value is not a CodePath Launcher Summary");
        }
        try {
            return JSON.readValue(line.substring(LINE_PREFIX.length()), LauncherSummary.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("CodePath Launcher Summary JSON is invalid", failure);
        }
    }
}
