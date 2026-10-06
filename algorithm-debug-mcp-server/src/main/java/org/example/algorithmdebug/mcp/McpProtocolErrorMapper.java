package org.example.algorithmdebug.mcp;

import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/** 把协议边界异常映射为不泄漏内部细节的稳定 JSON-RPC error。 */
public final class McpProtocolErrorMapper {
    private static final int REQUEST_CANCELLED = -32800;
    private static final int SERVER_BUSY = -32001;
    private static final String DATA_CODE = "code";
    private static final String PARSE_ERROR_CODE = "MCP_PARSE_ERROR";
    private static final String REQUEST_TOO_LARGE_CODE = "MCP_REQUEST_TOO_LARGE";
    private static final String INVALID_REQUEST_CODE = "MCP_INVALID_REQUEST";
    private static final String METHOD_NOT_FOUND_CODE = "MCP_METHOD_NOT_FOUND";
    private static final String REQUEST_CANCELLED_CODE = "MCP_REQUEST_CANCELLED";
    private static final String SERVER_BUSY_CODE = "MCP_SERVER_BUSY";
    private static final String INVALID_PARAMS_CODE = "MCP_INVALID_PARAMS";
    private static final String INTERNAL_ERROR_CODE = "MCP_INTERNAL_ERROR";

    /** @return 畸形 UTF-8 或 JSON 的标准 parse error */
    public JSONRPCError malformedJson() {
        return error(ErrorCodes.PARSE_ERROR, "Malformed JSON-RPC payload", PARSE_ERROR_CODE);
    }

    /** @return 超过 Server 硬预算的 invalid request */
    public JSONRPCError requestTooLarge() {
        return error(
                ErrorCodes.INVALID_REQUEST,
                "JSON-RPC payload exceeds the server limit",
                REQUEST_TOO_LARGE_CODE);
    }

    /** @return JSON 语法正确但不满足 JSON-RPC 信封契约的 invalid request */
    public JSONRPCError invalidRequest() {
        return error(
                ErrorCodes.INVALID_REQUEST,
                "JSON-RPC request is invalid",
                INVALID_REQUEST_CODE);
    }

    /** @return 未注册能力或方法的标准 method-not-found */
    public JSONRPCError methodNotFound() {
        return error(
                ErrorCodes.METHOD_NOT_FOUND,
                "JSON-RPC method is not supported",
                METHOD_NOT_FOUND_CODE);
    }

    /**
     * 映射请求执行期异常；返回内容不包含异常消息、路径或堆栈。
     *
     * @param failure 请求边界捕获的异常
     * @return 稳定协议错误
     */
    public JSONRPCError map(Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        if (failure instanceof CancellationException) {
            return error(REQUEST_CANCELLED, "Request was cancelled", REQUEST_CANCELLED_CODE);
        }
        if (failure instanceof RejectedExecutionException) {
            return error(SERVER_BUSY, "Server is not accepting requests", SERVER_BUSY_CODE);
        }
        if (failure instanceof IllegalArgumentException) {
            return error(
                    ErrorCodes.INVALID_PARAMS,
                    "Request parameters are invalid",
                    INVALID_PARAMS_CODE);
        }
        return error(
                ErrorCodes.INTERNAL_ERROR,
                "Internal server error",
                INTERNAL_ERROR_CODE);
    }

    private static JSONRPCError error(int code, String message, String stableCode) {
        return new JSONRPCError(code, message, Map.of(DATA_CODE, stableCode));
    }
}
