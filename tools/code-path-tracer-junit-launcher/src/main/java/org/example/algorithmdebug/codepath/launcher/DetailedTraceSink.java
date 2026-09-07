package org.example.algorithmdebug.codepath.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** TRACE 模式的同步完整行写入器。 */
final class DetailedTraceSink implements CodePathCaptureSink {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TraceJsonlSink sink;
    private long sequence;

    DetailedTraceSink(Path output, LauncherCodePathPlan.Budget budget) throws IOException {
        sink = new TraceJsonlSink(output, budget.maxBytes(), budget.maxEvents());
    }

    @Override
    public long enter(String methodKey, int selectedDepth, String parentMethodKey, Long parentEnterEventId,
            List<ProjectionValue> projections) throws IOException {
        long eventId = ++sequence;
        ObjectNode event = event(eventId, "METHOD_ENTER", methodKey, selectedDepth, projections);
        if (parentEnterEventId == null) event.putNull("parentSelectedEnterEventId");
        else event.put("parentSelectedEnterEventId", parentEnterEventId);
        append(event);
        return eventId;
    }

    @Override
    public void exit(String methodKey, int selectedDepth, long enterEventId,
            List<ProjectionValue> projections, Throwable thrown) throws IOException {
        ObjectNode event = event(++sequence, "METHOD_EXIT", methodKey, selectedDepth, projections);
        event.put("enterEventId", enterEventId);
        event.put("exitKind", thrown == null ? "RETURNED" : "THREW");
        if (thrown != null) event.put("throwableType", thrown.getClass().getName());
        append(event);
    }

    private ObjectNode event(long eventId, String eventType, String methodKey,
            int selectedDepth, List<ProjectionValue> projections) {
        MethodIdentity identity = MethodIdentity.parse(methodKey);
        ObjectNode event = JSON.createObjectNode();
        event.put("eventId", eventId);
        event.put("eventType", eventType);
        event.put("depth", selectedDepth);
        event.put("depthSemantics", "SELECTED_METHOD_DEPTH");
        event.put("className", identity.className());
        event.put("methodName", identity.methodName());
        event.put("descriptor", identity.descriptor());
        event.set("projections", JSON.valueToTree(projections));
        return event;
    }

    private void append(ObjectNode event) throws IOException {
        sink.append(JSON.writeValueAsString(event));
    }

    @Override public boolean stopped() { return sink.limitReached(); }
    @Override public TraceJsonlSink.Limit limit() { return sink.result().limit(); }
    @Override public long eventsWritten() { return sink.result().eventsWritten(); }
    @Override public long bytesWritten() { return sink.result().bytesWritten(); }
    @Override public List<String> reasons() {
        return switch (limit()) {
            case NONE -> List.of();
            case EVENTS -> List.of("EVENT_BUDGET_EXCEEDED");
            case OUTPUT_BYTES -> List.of("OUTPUT_BUDGET_EXCEEDED");
        };
    }
    @Override public void close() throws IOException { sink.close(); }
}
