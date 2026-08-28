package org.example.algorithmdebug.casecore.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/** 将结构化事件确定性格式化为可直接阅读的英文文本。 */
public final class AgentLogFormatter {
    private final ZoneId zone;
    private final SensitiveLogSanitizer sanitizer;

    public AgentLogFormatter(ZoneId zone, SensitiveLogSanitizer sanitizer) {
        if (zone == null || sanitizer == null) {
            throw new IllegalArgumentException("Log formatter dependencies must not be null");
        }
        this.zone = zone;
        this.sanitizer = sanitizer;
    }

    /** 格式化事件；异常堆栈使用后续 `STACK` 行保存。 */
    public String format(AgentLogEvent event, Instant timestamp) {
        StringBuilder result = new StringBuilder(512);
        result.append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestamp.atZone(zone)))
                .append(' ').append(event.level())
                .append(" component=").append(token(event.component()))
                .append(" event=").append(token(event.event()))
                .append(" outcome=").append(token(event.outcome()));
        appendContext(result, event.context());
        new TreeMap<>(event.facts()).forEach((key, value) ->
                result.append(' ').append(token(key)).append("=\"")
                        .append(sanitizer.singleLine(value)).append('\"'));
        result.append(" message=\"").append(sanitizer.singleLine(event.message())).append("\"")
                .append(System.lineSeparator());
        if (event.failure() != null) {
            StringWriter stack = new StringWriter();
            event.failure().printStackTrace(new PrintWriter(stack));
            for (String line : sanitizer.multiline(stack.toString()).split("\\n", -1)) {
                if (!line.isEmpty()) {
                    result.append("STACK ").append(line).append(System.lineSeparator());
                }
            }
        }
        return result.toString();
    }

    private static void appendContext(StringBuilder result, AgentLogContext context) {
        append(result, "projectId", context.projectId());
        append(result, "caseId", context.caseId());
        append(result, "analysisId", context.analysisId());
        append(result, "runId", context.runId());
        append(result, "planId", context.planId());
        append(result, "collectionId", context.collectionId());
        append(result, "evidenceId", context.evidenceId());
        append(result, "artifactId", context.artifactId());
    }

    private static void append(StringBuilder result, String key, String value) {
        if (value != null && !value.isBlank()) {
            result.append(' ').append(key).append('=').append(token(value));
        }
    }

    private static String token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            return "unknown";
        }
        return value;
    }
}
