package org.example.algorithmdebug.casecore.logging;

import java.util.Map;

/**
 * Java Agent 执行观测端口。实现必须保证日志失败不改变业务控制流。
 */
@FunctionalInterface
public interface AgentExecutionLog {
    /** 旁路写入事件；实现不得把写入异常传播给调用方。 */
    void write(AgentLogEvent event);

    /** @return 不产生文件的默认实现。 */
    static AgentExecutionLog disabled() {
        return event -> { };
    }

    default void info(
            AgentLogContext context, String component, String event,
            String outcome, String message) {
        info(context, component, event, outcome, message, Map.of());
    }

    default void info(
            AgentLogContext context, String component, String event,
            String outcome, String message, Map<String, String> facts) {
        write(new AgentLogEvent(
                AgentLogLevel.INFO, context, component, event, outcome, message, facts, null));
    }

    default void warn(
            AgentLogContext context, String component, String event,
            String outcome, String message, Map<String, String> facts) {
        write(new AgentLogEvent(
                AgentLogLevel.WARN, context, component, event, outcome, message, facts, null));
    }

    default void error(
            AgentLogContext context, String component, String event,
            String outcome, String message, Map<String, String> facts, Throwable failure) {
        write(new AgentLogEvent(
                AgentLogLevel.ERROR, context, component, event, outcome, message, facts, failure));
    }
}
