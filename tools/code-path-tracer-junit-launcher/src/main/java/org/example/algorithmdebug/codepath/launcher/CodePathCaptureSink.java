package org.example.algorithmdebug.codepath.launcher;

import java.io.IOException;
import java.util.List;

/** 已通过 Scope 门禁的精确方法事件接收端。 */
interface CodePathCaptureSink extends AutoCloseable {
    long enter(String methodKey, int selectedDepth, String parentMethodKey, Long parentEnterEventId,
            List<ProjectionValue> projections) throws IOException;
    void exit(String methodKey, int selectedDepth, long enterEventId,
            List<ProjectionValue> projections, Throwable thrown) throws IOException;
    boolean stopped();
    TraceJsonlSink.Limit limit();
    long eventsWritten();
    long bytesWritten();
    List<String> reasons();
    default void runtimeReasons(List<String> values) {}
    default void scopeStatistics(ScopeCaptureStatistics statistics) {}
    @Override void close() throws IOException;

    record ScopeCaptureStatistics(
            boolean enabled,
            String methodKey,
            long observed,
            long matched,
            long captured,
            long skippedByWindow,
            long unavailable,
            long incompleteCaptured) {}
}
