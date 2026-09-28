package org.example.algorithmdebug.mcp;

import java.nio.file.Path;
import org.example.algorithmdebug.contracts.ProjectId;

/** 一次 MCP 工具调用经冻结 Workspace、Project 和 wire requestId 解析后的可信上下文。 */
public record McpRequestContext(
        String workspaceId,
        Path workspaceRoot,
        ProjectId projectId,
        Path projectRoot,
        McpRequestId requestId,
        String invocationToken) {
    /** 拒绝不完整上下文；路径已由 Resolver 做真实路径和注册关系校验。 */
    public McpRequestContext {
        if (workspaceId == null || workspaceId.isBlank() || workspaceRoot == null
                || projectId == null || projectRoot == null || requestId == null
                || invocationToken == null || !invocationToken.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("MCP request context is invalid");
        }
    }
}
