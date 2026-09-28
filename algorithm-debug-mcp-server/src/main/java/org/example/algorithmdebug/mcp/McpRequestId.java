package org.example.algorithmdebug.mcp;

/**
 * 保留 JSON-RPC requestId 的字符串/整数类型，避免数值 {@code 1} 与字符串 {@code "1"}
 * 在取消和并发登记时发生碰撞。
 */
public sealed interface McpRequestId permits McpRequestId.Text, McpRequestId.Numeric {
    /** @return 写回 JSON-RPC 信封时使用的原生字符串或整数值 */
    Object wireValue();

    /**
     * 从官方 SDK 已校验的 wire id 建立类型化标识。
     *
     * @param value SDK JSON-RPC request id
     * @return 类型化标识
     */
    static McpRequestId fromWire(Object value) {
        if (value instanceof String text) {
            return new Text(text);
        }
        if (value instanceof Integer integer) {
            return new Numeric(integer.longValue());
        }
        if (value instanceof Long number) {
            return new Numeric(number);
        }
        throw new IllegalArgumentException("MCP requestId must be a string or integer");
    }

    /** 字符串 requestId。 */
    record Text(String value) implements McpRequestId {
        /** 校验字符串长度预算；空字符串仍是合法 JSON-RPC id。 */
        public Text {
            if (value == null || value.length() > McpServerLimits.MAX_REQUEST_ID_LENGTH) {
                throw new IllegalArgumentException("MCP string requestId is invalid");
            }
        }

        @Override
        public Object wireValue() {
            return value;
        }
    }

    /** 整数 requestId；Integer 与 Long 的同值在协议上视为同一个整数。 */
    record Numeric(long value) implements McpRequestId {
        @Override
        public Object wireValue() {
            return value;
        }
    }
}
