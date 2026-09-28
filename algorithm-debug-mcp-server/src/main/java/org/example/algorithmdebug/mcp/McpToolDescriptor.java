package org.example.algorithmdebug.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;

/** 一个模型可调用工具与唯一 Coordinator Action、输入类型和 Schema 的不可变绑定。 */
public record McpToolDescriptor(
        String name,
        AnalysisActionType actionType,
        String description,
        Class<?> inputType,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema) {

    /** 校验目录项完整性；Catalog 负责提供深度不可变 Schema。 */
    public McpToolDescriptor {
        if (name == null || name.isBlank() || !name.matches("[a-z][a-z0-9_]*")
                || actionType == null || description == null || description.isBlank()
                || inputType == null || inputSchema == null || outputSchema == null) {
            throw new IllegalArgumentException("MCP tool descriptor is invalid");
        }
    }

    /** @return MCP SDK 使用的标准工具声明 */
    Tool toTool() {
        return Tool.builder(name, inputSchema)
                .description(description)
                .outputSchema(outputSchema)
                .build();
    }
}
