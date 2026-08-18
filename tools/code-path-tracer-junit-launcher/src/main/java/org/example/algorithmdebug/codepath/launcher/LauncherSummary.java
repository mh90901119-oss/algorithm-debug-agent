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
        String detail) {

    /** 父进程定位 Summary 的稳定前缀。 */
    public static final String LINE_PREFIX = "ADA_CODEPATH_SUMMARY=";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 校验计数和有界详情。 */
    public LauncherSummary {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(limit, "limit");
        if (testsFound < 0 || testsSucceeded < 0 || testsAborted < 0 || testsFailed < 0
                || testsSucceeded + testsAborted + testsFailed > testsFound
                || eventsWritten < 0 || bytesWritten < 0) {
            throw new IllegalArgumentException("Launcher Summary 计数不能为负数");
        }
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.length() > 2_048) {
            throw new IllegalArgumentException("Launcher Summary detail 超长");
        }
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
            throw new IllegalStateException("无法序列化 Launcher Summary", failure);
        }
    }

    /** 解析父进程捕获到的结构化行。 */
    public static LauncherSummary parseStructuredLine(String line) {
        if (line == null || !line.startsWith(LINE_PREFIX)) {
            throw new IllegalArgumentException("不是 CodePath Launcher Summary");
        }
        try {
            return JSON.readValue(line.substring(LINE_PREFIX.length()), LauncherSummary.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("CodePath Launcher Summary JSON 非法", failure);
        }
    }
}
