package org.example.algorithmdebug.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 在官方 SDK stdio transport 前执行单帧有界预校验。
 *
 * <p>每个合法请求先登记全局有界槽位，普通非工具帧保持原始字节语义并继续交给 SDK；仅为
 * SDK 2.0.1 缺失的工具 requestId 关联与取消通知执行有界兼容处理。它不注册业务方法、
 * 不解释工具参数，也不实现 MCP 会话。</p>
 */
final class McpProtocolInputStream extends InputStream {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final String REQUEST_ID_META_KEY = "org.example.algorithmdebug/request-id";
    private static final String CANCELLATION_METHOD = "notifications/cancelled";
    private static final byte LINE_FEED = (byte) '\n';
    private static final int CARRIAGE_RETURN = '\r';
    private static final int PUSHBACK_BYTES = 1;
    private static final int INITIAL_FRAME_BUFFER_BYTES = 8 * 1024;

    private final PushbackInputStream source;
    private final McpProtocolOutputStream protocolOutput;
    private final McpJsonMapper jsonMapper;
    private final McpProtocolErrorMapper errors;
    private final McpServerLifecycle lifecycle;
    private final int maximumFrameBytes;
    private byte[] currentFrame = new byte[0];
    private int currentOffset;

    McpProtocolInputStream(
            InputStream source,
            McpProtocolOutputStream protocolOutput,
            McpJsonMapper jsonMapper,
            McpProtocolErrorMapper errors,
            McpServerLifecycle lifecycle,
            int maximumFrameBytes) {
        if (source == null || protocolOutput == null || jsonMapper == null || errors == null
                || lifecycle == null || maximumFrameBytes <= 0) {
            throw new IllegalArgumentException("Protocol input dependencies are invalid");
        }
        this.source = new PushbackInputStream(source, PUSHBACK_BYTES);
        this.protocolOutput = protocolOutput;
        this.jsonMapper = jsonMapper;
        this.errors = errors;
        this.lifecycle = lifecycle;
        this.maximumFrameBytes = maximumFrameBytes;
    }

    @Override
    public int read() throws IOException {
        while (currentOffset >= currentFrame.length) {
            if (!loadNextValidFrame()) {
                return -1;
            }
        }
        return Byte.toUnsignedInt(currentFrame[currentOffset++]);
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) {
            return 0;
        }
        int first = read();
        if (first < 0) {
            return -1;
        }
        destination[offset] = (byte) first;
        int copied = 1;
        while (copied < length && currentOffset < currentFrame.length) {
            int available = Math.min(length - copied, currentFrame.length - currentOffset);
            System.arraycopy(currentFrame, currentOffset, destination, offset + copied, available);
            currentOffset += available;
            copied += available;
        }
        return copied;
    }

    private boolean loadNextValidFrame() throws IOException {
        while (true) {
            FrameRead frame = readFrame();
            if (frame.endOfStream()) {
                return false;
            }
            if (frame.exceededLimit()) {
                writeError(errors.requestTooLarge());
                continue;
            }
            final String json;
            try {
                json = decodeUtf8(frame.bytes());
            } catch (CharacterCodingException failure) {
                writeError(errors.malformedJson());
                continue;
            }
            if (!hasOneStrictJsonValue(json)) {
                writeError(errors.malformedJson());
                continue;
            }
            final McpSchema.JSONRPCMessage message;
            try {
                message = McpSchema.deserializeJsonRpcMessage(jsonMapper, json);
            } catch (RuntimeException | IOException failure) {
                writeError(errors.invalidRequest());
                continue;
            }
            byte[] forwarded = prepareForSdk(message, frame.bytes());
            if (forwarded == null) {
                continue;
            }
            currentFrame = withLineFeed(forwarded);
            currentOffset = 0;
            return true;
        }
    }

    private byte[] prepareForSdk(McpSchema.JSONRPCMessage message, byte[] original)
            throws IOException {
        if (!McpSchema.JSONRPC_VERSION.equals(message.jsonrpc())) {
            if (message instanceof McpSchema.JSONRPCRequest request) {
                writeError(request.id(), errors.invalidRequest());
            }
            return null;
        }
        if (message instanceof McpSchema.JSONRPCNotification notification
                && CANCELLATION_METHOD.equals(notification.method())) {
            routeCancellation(notification);
            return null;
        }
        if (!(message instanceof McpSchema.JSONRPCRequest request)) {
            return original;
        }
        final McpRequestId requestId;
        try {
            requestId = McpRequestId.fromWire(request.id());
        } catch (IllegalArgumentException invalidId) {
            writeError(request.id(), errors.invalidRequest());
            return null;
        }
        try {
            lifecycle.observeCall(requestId);
        } catch (RejectedExecutionException rejected) {
            writeError(requestId.wireValue(), errors.map(rejected));
            return null;
        }
        if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
            return original;
        }
        try {
            CallToolRequest toolCall = jsonMapper.convertValue(
                    request.params(), new TypeRef<CallToolRequest>() { });
            if (toolCall == null) {
                throw new IllegalArgumentException("tools/call params must not be null");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (toolCall.meta() != null) {
                metadata.putAll(toolCall.meta());
            }
            metadata.put(REQUEST_ID_META_KEY, requestId.wireValue());
            CallToolRequest correlated = new CallToolRequest(
                    toolCall.name(), toolCall.arguments(),
                    Collections.unmodifiableMap(metadata));
            byte[] transformed = jsonMapper.writeValueAsBytes(new McpSchema.JSONRPCRequest(
                    request.jsonrpc(), request.method(), request.id(), correlated));
            if (transformed.length > maximumFrameBytes) {
                lifecycle.completeUnclaimedCall(requestId);
                writeError(requestId.wireValue(), errors.requestTooLarge());
                return null;
            }
            return transformed;
        } catch (RuntimeException | IOException failure) {
            lifecycle.completeUnclaimedCall(requestId);
            writeError(requestId.wireValue(), errors.map(failure));
            return null;
        }
    }

    private void routeCancellation(McpSchema.JSONRPCNotification notification) {
        try {
            CancellationNotificationParams params = jsonMapper.convertValue(
                    notification.params(), new TypeRef<CancellationNotificationParams>() { });
            lifecycle.cancel(McpRequestId.fromWire(params.requestId()));
        } catch (RuntimeException ignored) {
            // JSON-RPC notification 没有响应；非法取消参数只能安全忽略，不能污染 stdout。
        }
    }

    private static boolean hasOneStrictJsonValue(String json) {
        try {
            return STRICT_JSON.readTree(json) != null;
        } catch (IOException failure) {
            return false;
        }
    }

    private FrameRead readFrame() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                Math.min(maximumFrameBytes, INITIAL_FRAME_BUFFER_BYTES));
        boolean exceeded = false;
        boolean observed = false;
        while (true) {
            int value = source.read();
            if (value < 0) {
                return observed
                        ? new FrameRead(buffer.toByteArray(), exceeded, false)
                        : FrameRead.end();
            }
            observed = true;
            if (value == '\n') {
                break;
            }
            if (value == CARRIAGE_RETURN) {
                int next = source.read();
                if (next >= 0 && next != '\n') {
                    source.unread(next);
                }
                break;
            }
            if (buffer.size() < maximumFrameBytes) {
                buffer.write(value);
            } else {
                exceeded = true;
            }
        }
        return new FrameRead(buffer.toByteArray(), exceeded, false);
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static byte[] withLineFeed(byte[] frame) {
        byte[] delimited = java.util.Arrays.copyOf(frame, frame.length + 1);
        delimited[frame.length] = LINE_FEED;
        return delimited;
    }

    private void writeError(JSONRPCResponse.JSONRPCError error) throws IOException {
        writeError(null, error);
    }

    private void writeError(Object requestId, JSONRPCResponse.JSONRPCError error)
            throws IOException {
        byte[] json = jsonMapper.writeValueAsBytes(
                new ErrorEnvelope(McpSchema.JSONRPC_VERSION, requestId, error));
        protocolOutput.writeUntrackedFrame(json);
    }

    private record FrameRead(byte[] bytes, boolean exceededLimit, boolean endOfStream) {
        private static FrameRead end() {
            return new FrameRead(new byte[0], false, true);
        }
    }

    /** SDK 2.0.1 不允许 null id，因此传输适配错误使用这个规范信封。 */
    private record ErrorEnvelope(
            String jsonrpc, Object id, JSONRPCResponse.JSONRPCError error) {
    }

    private record CancellationNotificationParams(Object requestId, String reason) {
    }
}
