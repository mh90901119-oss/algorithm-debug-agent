package org.example.algorithmdebug.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.example.algorithmdebug.contracts.coordination.CoordinatedToolResult;

/** 把 Coordinator 领域结果映射为普通 MCP Tool 结果；领域失败绝不伪装成协议错误。 */
public final class McpResultMapper {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String TRUNCATION_MESSAGE =
            "Tool data exceeded the inline MCP budget; inspect the returned artifact references.";

    private final ObjectMapper mapper;

    /** 使用统一严格 JSON 配置创建结果映射器。 */
    public McpResultMapper() {
        this(McpJsonSupport.strictMapper());
    }

    McpResultMapper(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        this.mapper = mapper;
    }

    /**
     * 保留 outcome/code/control/artifacts，并在 data 过大时只替换为有界截断说明。
     */
    public CallToolResult map(CoordinatedToolResult<?> result) {
        if (result == null) {
            throw new IllegalArgumentException("Coordinated result must not be null");
        }
        Map<String, Object> structured = convert(result);
        if (serializedSize(structured) > McpServerLimits.MAX_RESULT_BYTES) {
            structured = truncated(result);
        }
        if (serializedSize(structured) > McpServerLimits.MAX_RESULT_BYTES) {
            throw new IllegalStateException("MCP result envelope exceeds its hard budget");
        }
        String summary = boundedSummary(result.outcome().name() + " " + result.code()
                + ": " + result.message());
        return CallToolResult.builder()
                .content(List.of(new TextContent(summary)))
                .structuredContent(structured)
                .isError(false)
                .build();
    }

    private Map<String, Object> convert(CoordinatedToolResult<?> result) {
        return immutableMap(mapper.convertValue(result, MAP_TYPE));
    }

    private Map<String, Object> truncated(CoordinatedToolResult<?> result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", result.schemaVersion());
        value.put("outcome", result.outcome().name());
        value.put("code", result.code());
        value.put("message", result.message());
        value.put("data", Map.of(
                "truncated", true,
                "reason", TRUNCATION_MESSAGE));
        value.put("artifacts", mapper.convertValue(result.artifacts(), Object.class));
        value.put("control", mapper.convertValue(result.control(), Object.class));
        return immutableMap(value);
    }

    private int serializedSize(Map<String, Object> value) {
        try {
            return mapper.writeValueAsBytes(value).length;
        } catch (IOException failure) {
            throw new IllegalStateException("MCP result cannot be serialized", failure);
        }
    }

    private static String boundedSummary(String summary) {
        return summary.length() <= McpServerLimits.MAX_RESULT_SUMMARY_CHARS
                ? summary
                : summary.substring(0, McpServerLimits.MAX_RESULT_SUMMARY_CHARS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return (Map<String, Object>) immutableJson(value);
    }

    private static Object immutableJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), immutableJson(nested)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(McpResultMapper::immutableJson).toList();
        }
        return value;
    }
}
