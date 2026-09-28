package org.example.algorithmdebug.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 原样转发 SDK stdout，并在完整响应写出后释放尚未被工具处理器释放的 requestId 占位。
 * 本类不生成响应、不改变帧内容，也不参与业务结果映射。
 */
final class McpProtocolOutputStream extends OutputStream {
    private static final int INITIAL_TRACKING_BUFFER_BYTES = 8 * 1024;
    private static final int LINE_FEED = '\n';

    private final OutputStream delegate;
    private final McpJsonMapper jsonMapper;
    private final McpServerLifecycle lifecycle;
    private final int maximumTrackedBytes;
    private final ByteArrayOutputStream tracked;
    private final List<McpRequestId> pendingCompletions = new ArrayList<>();
    private boolean tracking = true;

    McpProtocolOutputStream(
            OutputStream delegate,
            McpJsonMapper jsonMapper,
            McpServerLifecycle lifecycle,
            int maximumTrackedBytes) {
        if (delegate == null || jsonMapper == null || lifecycle == null
                || maximumTrackedBytes <= 0) {
            throw new IllegalArgumentException("Protocol output dependencies are invalid");
        }
        this.delegate = delegate;
        this.jsonMapper = jsonMapper;
        this.lifecycle = lifecycle;
        this.maximumTrackedBytes = maximumTrackedBytes;
        this.tracked = new ByteArrayOutputStream(
                Math.min(maximumTrackedBytes, INITIAL_TRACKING_BUFFER_BYTES));
    }

    @Override
    public synchronized void write(int value) throws IOException {
        delegate.write(value);
        track(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        delegate.write(bytes, offset, length);
        for (int index = offset; index < offset + length; index++) {
            track(Byte.toUnsignedInt(bytes[index]));
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        delegate.flush();
        completeFlushedResponses();
    }

    /** 写入传输适配器生成的错误帧，不把它误判成 SDK 对活动请求的终态响应。 */
    synchronized void writeUntrackedFrame(byte[] json) throws IOException {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        delegate.write(json);
        delegate.write(LINE_FEED);
        delegate.flush();
        completeFlushedResponses();
    }

    private void track(int value) {
        if (value == LINE_FEED) {
            McpRequestId completed = trackedResponseId();
            if (completed != null) {
                pendingCompletions.add(completed);
            }
            tracked.reset();
            tracking = true;
            return;
        }
        if (tracking && tracked.size() < maximumTrackedBytes) {
            tracked.write(value);
        } else {
            tracking = false;
        }
    }

    private McpRequestId trackedResponseId() {
        if (!tracking || tracked.size() == 0) {
            return null;
        }
        try {
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(
                    jsonMapper, tracked.toString(StandardCharsets.UTF_8));
            if (message instanceof McpSchema.JSONRPCResponse response
                    && response.id() != null) {
                return McpRequestId.fromWire(response.id());
            }
        } catch (RuntimeException | IOException ignored) {
            // stdout 的合法性仍由 SDK/错误适配器负责；追踪失败不能改变已经写出的协议帧。
        }
        return null;
    }

    private void completeFlushedResponses() {
        pendingCompletions.forEach(lifecycle::completeUnclaimedCall);
        pendingCompletions.clear();
    }
}
