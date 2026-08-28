package org.example.algorithmdebug.casecore.logging;

import java.util.Map;

/** 一条不包含业务正文的结构化 Java Agent 日志事件。 */
public record AgentLogEvent(
        AgentLogLevel level,
        AgentLogContext context,
        String component,
        String event,
        String outcome,
        String message,
        Map<String, String> facts,
        Throwable failure) {

    public AgentLogEvent {
        if (level == null || context == null || blank(component) || blank(event)
                || blank(outcome) || blank(message) || facts == null) {
            throw new IllegalArgumentException("Agent log event fields must be valid");
        }
        facts = Map.copyOf(facts);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
