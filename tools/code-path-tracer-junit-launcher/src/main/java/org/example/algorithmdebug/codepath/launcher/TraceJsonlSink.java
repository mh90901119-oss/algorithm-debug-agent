package org.example.algorithmdebug.codepath.launcher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * CodePath 回调使用的同步 JSONL 落盘器。
 *
 * <p>每次调用只写一个完整 UTF-8 行；达到硬预算后拒绝后续事件，但不打断目标 UT。</p>
 */
public final class TraceJsonlSink implements AutoCloseable {
    /** 单次 Raw Trace 的硬字节上限。 */
    public static final long HARD_MAX_OUTPUT_BYTES = 50L * 1024 * 1024;
    /** 单次 Raw Trace 的硬事件上限。 */
    public static final long HARD_MAX_EVENTS = 1_000_000;

    private static final byte[] NEWLINE = {'\n'};

    private final OutputStream output;
    private final long maxOutputBytes;
    private final long maxEvents;
    private long bytesWritten;
    private long eventsWritten;
    private Limit limit = Limit.NONE;
    private boolean closed;

    /**
     * 创建 write-once 流式 Sink。
     *
     * @param traceFile 新建的 Raw JSONL 文件
     * @param maxOutputBytes 本次字节预算，不得超过 50 MiB
     * @param maxEvents 本次事件预算，不得超过 1,000,000
     */
    public TraceJsonlSink(Path traceFile, long maxOutputBytes, long maxEvents) throws IOException {
        this(openTraceFile(traceFile, maxOutputBytes, maxEvents), maxOutputBytes, maxEvents);
    }

    TraceJsonlSink(OutputStream output, long maxOutputBytes, long maxEvents) {
        validateBudgets(maxOutputBytes, maxEvents);
        this.output = new BufferedOutputStream(Objects.requireNonNull(output, "output"));
        this.maxOutputBytes = maxOutputBytes;
        this.maxEvents = maxEvents;
    }

    private static OutputStream openTraceFile(
            Path traceFile, long maxOutputBytes, long maxEvents) throws IOException {
        validateBudgets(maxOutputBytes, maxEvents);
        Path file = Objects.requireNonNull(traceFile, "traceFile").toAbsolutePath().normalize();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.newOutputStream(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void validateBudgets(long maxOutputBytes, long maxEvents) {
        if (maxOutputBytes <= 0 || maxOutputBytes > HARD_MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("maxOutputBytes exceeds the Agent hard limit");
        }
        if (maxEvents <= 0 || maxEvents > HARD_MAX_EVENTS) {
            throw new IllegalArgumentException("maxEvents exceeds the Agent hard limit");
        }
    }

    /**
     * 尝试写入一个完整 JSON 行。达到预算后返回 false，调用方应继续执行目标测试。
     *
     * @param jsonLine 不含换行符的 JSON 文本
     * @return 是否写入
     */
    public synchronized boolean append(String jsonLine) throws IOException {
        ensureOpen();
        String value = Objects.requireNonNull(jsonLine, "jsonLine");
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("jsonLine must not contain line breaks");
        }
        if (limit != Limit.NONE) {
            return false;
        }
        if (eventsWritten >= maxEvents) {
            limit = Limit.EVENTS;
            return false;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        long lineBytes = Math.addExact(bytes.length, NEWLINE.length);
        if (lineBytes > maxOutputBytes - bytesWritten) {
            limit = Limit.OUTPUT_BYTES;
            return false;
        }
        output.write(bytes);
        output.write(NEWLINE);
        bytesWritten += lineBytes;
        eventsWritten++;
        if (eventsWritten >= maxEvents) {
            limit = Limit.EVENTS;
        } else if (bytesWritten >= maxOutputBytes) {
            limit = Limit.OUTPUT_BYTES;
        }
        return true;
    }

    /** @return 是否已经命中事件数或字节数预算，调用方应停止后续格式化。 */
    public synchronized boolean limitReached() {
        return limit != Limit.NONE;
    }

    /** @return 当前确定性计数和命中的首个预算。 */
    public synchronized Result result() {
        return new Result(eventsWritten, bytesWritten, limit);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            output.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("TraceJsonlSink is closed");
        }
    }

    /** 达到的硬预算类型。 */
    public enum Limit { NONE, OUTPUT_BYTES, EVENTS }

    /** Sink 的有界完成事实。 */
    public record Result(long eventsWritten, long bytesWritten, Limit limit) {
        /** 校验计数与预算状态。 */
        public Result {
            if (eventsWritten < 0 || bytesWritten < 0) {
                throw new IllegalArgumentException("Sink counts must not be negative");
            }
            Objects.requireNonNull(limit, "limit");
        }
    }
}
