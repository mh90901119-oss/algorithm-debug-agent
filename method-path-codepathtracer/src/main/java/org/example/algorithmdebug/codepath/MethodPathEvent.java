package org.example.algorithmdebug.codepath;

/** CodePath 外部 Launcher 当前 JSONL 事件格式。 */
public record MethodPathEvent(
        long eventId,
        String eventType,
        int depth,
        String threadName,
        String className,
        String methodName,
        String descriptor) {

    /** 校验事件为有界方法进入/退出事实。 */
    public MethodPathEvent {
        if (eventId < 1 || !("METHOD_ENTER".equals(eventType) || "METHOD_EXIT".equals(eventType))
                || depth < 0 || depth > 1_000_000) {
            throw new IllegalArgumentException("CodePath 事件身份、类型或深度非法");
        }
        threadName = bounded(threadName, "threadName", 1_024);
        className = bounded(className, "className", 1_024);
        methodName = bounded(methodName, "methodName", 512);
        if (descriptor != null && (descriptor.isBlank() || descriptor.length() > 2_048)) {
            throw new IllegalArgumentException("descriptor 非法");
        }
    }

    private static String bounded(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " 非法");
        }
        return value;
    }
}
