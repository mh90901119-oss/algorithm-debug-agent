package org.example.algorithmdebug.mcp;

import java.time.Duration;

/** MCP 传输、并发和关闭预算的唯一来源。 */
public final class McpServerLimits {
    /** 单个 stdio JSON-RPC 帧的硬字节上限。 */
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    /** 单个工具结构化结果的硬字节上限，为 JSON-RPC 信封保留余量。 */
    public static final int MAX_RESULT_BYTES = 768 * 1024;
    /** 工具的人类可读摘要最大字符数。 */
    public static final int MAX_RESULT_SUMMARY_CHARS = 8 * 1024;
    /** 单个内置 Schema 资源最大字节数。 */
    public static final int MAX_SCHEMA_BYTES = 512 * 1024;
    /** 默认活动请求预算。 */
    public static final int DEFAULT_ACTIVE_CALLS = 16;
    /** 活动请求硬上限。 */
    public static final int MAX_ACTIVE_CALLS = 64;
    /** 默认 Server 关闭等待。 */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    /** Server 关闭等待硬上限。 */
    public static final Duration MAX_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    /** SDK 单请求处理的默认上限。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
    /** requestId 的最大字符数。 */
    public static final int MAX_REQUEST_ID_LENGTH = 256;

    private McpServerLimits() {
    }
}
