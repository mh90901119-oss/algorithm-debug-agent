package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceJsonlSinkTest {
    @TempDir Path temp;

    @Test
    void writesOnlyCompleteUtf8LinesWithinExactByteLimit() throws Exception {
        Path trace = temp.resolve("trace.jsonl");
        String first = "{\"name\":\"晶圆\"}";
        long exactBytes = (first + "\n").getBytes(StandardCharsets.UTF_8).length;

        TraceJsonlSink.Result result;
        try (TraceJsonlSink sink = new TraceJsonlSink(trace, exactBytes, 10)) {
            assertTrue(sink.append(first));
            assertFalse(sink.append("{\"second\":true}"));
            result = sink.result();
        }

        assertEquals(first + "\n", Files.readString(trace, StandardCharsets.UTF_8));
        assertEquals(1, result.eventsWritten());
        assertEquals(exactBytes, result.bytesWritten());
        assertEquals(TraceJsonlSink.Limit.OUTPUT_BYTES, result.limit());
    }

    @Test
    void stopsAtEventLimitWithoutPreventingCallerFromContinuing() throws Exception {
        Path trace = temp.resolve("events.jsonl");
        boolean targetContinued = false;
        try (TraceJsonlSink sink = new TraceJsonlSink(trace, 1_024, 1)) {
            assertTrue(sink.append("{\"event\":1}"));
            assertTrue(sink.limitReached());
            assertFalse(sink.append("{\"event\":2}"));
            targetContinued = true;
            assertEquals(TraceJsonlSink.Limit.EVENTS, sink.result().limit());
        }
        assertTrue(targetContinued);
        assertEquals(1, Files.readAllLines(trace, StandardCharsets.UTF_8).size());
    }

    @Test
    void serializesConcurrentCallbacksWithoutBrokenLines() throws Exception {
        Path trace = temp.resolve("concurrent.jsonl");
        int workers = 8;
        int perWorker = 100;
        var executor = Executors.newFixedThreadPool(workers);
        try (TraceJsonlSink sink = new TraceJsonlSink(trace, 1_000_000, workers * perWorker)) {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int id = worker;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < perWorker; index++) {
                        assertTrue(sink.append("{\"worker\":" + id + ",\"event\":" + index + "}"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        List<String> lines = Files.readAllLines(trace, StandardCharsets.UTF_8);
        assertEquals(workers * perWorker, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.startsWith("{") && line.endsWith("}")));
    }

    @Test
    void rejectsBudgetsAboveAgentHardLimits() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TraceJsonlSink(temp.resolve("too-large.jsonl"), 50L * 1024 * 1024 + 1, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TraceJsonlSink(temp.resolve("too-many.jsonl"), 1_024, 1_000_001));
    }

    @Test
    void buffersEventsInsteadOfFlushingEveryCallback() throws Exception {
        CountingOutputStream output = new CountingOutputStream();

        try (TraceJsonlSink sink = new TraceJsonlSink(output, 1_024, 10)) {
            assertTrue(sink.append("{\"event\":1}"));
            assertTrue(sink.append("{\"event\":2}"));
            assertEquals(0, output.flushCount);
        }

        assertTrue(output.flushCount <= 1);
        assertEquals("{\"event\":1}\n{\"event\":2}\n", output.content());
    }

    @Test
    void acceptsOneMillionBoundedCallbacksWithoutPerEventFlush() throws Exception {
        CountingOutputStream output = new CountingOutputStream();
        String event = "{\"e\":1}";
        int eventBytes = (event + "\n").getBytes(StandardCharsets.UTF_8).length;
        TraceJsonlSink.Result result;

        try (TraceJsonlSink sink = new TraceJsonlSink(
                output, TraceJsonlSink.HARD_MAX_OUTPUT_BYTES,
                TraceJsonlSink.HARD_MAX_EVENTS)) {
            for (int index = 0; index < TraceJsonlSink.HARD_MAX_EVENTS; index++) {
                assertTrue(sink.append(event));
            }
            assertEquals(0, output.flushCount);
            result = sink.result();
        }

        assertEquals(TraceJsonlSink.HARD_MAX_EVENTS, result.eventsWritten());
        assertEquals(TraceJsonlSink.HARD_MAX_EVENTS * eventBytes, result.bytesWritten());
        assertEquals(result.bytesWritten(), output.size());
        assertEquals(TraceJsonlSink.Limit.EVENTS, result.limit());
    }

    private static final class CountingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private int flushCount;

        @Override
        public void write(int value) {
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            flushCount++;
            delegate.flush();
        }

        private String content() {
            return delegate.toString(StandardCharsets.UTF_8);
        }

        private int size() {
            return delegate.size();
        }
    }
}
