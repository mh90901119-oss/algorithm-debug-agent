package org.example.algorithmdebug.mcp;

import java.time.Duration;

/** MCP 传输、并发和关闭预算的唯一来源。 */
public final class McpServerLimits {
    /** 单个 stdio JSON-RPC 帧的硬字节上限。 */
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    /**
     * Coordinator 在回显一个请求预算内的 ConclusionCandidate 之外，可追加的
     * Decision、ControlView 和结果信封预算。
     */
    public static final int MAX_COORDINATION_RESPONSE_OVERHEAD_BYTES = 512 * 1024;
    /**
     * 单个工具结构化结果的硬字节上限。该上限必须容纳请求预算内的 Candidate 及
     * Coordinator 生成的有界控制信息，避免 Finalization 被通用截断逻辑破坏。
     */
    public static final int MAX_RESULT_BYTES =
            MAX_REQUEST_BYTES + MAX_COORDINATION_RESPONSE_OVERHEAD_BYTES;
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
