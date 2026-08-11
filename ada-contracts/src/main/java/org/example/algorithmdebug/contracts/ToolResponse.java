package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * Adapter、CLI 与客户端薄适配层之间使用的统一工具响应。
 * 后续动作由大模型依据事实与 Skill 决策，不由响应内的固定状态机规定。
 */
public record ToolResponse<T>(
        String schemaVersion,
        boolean success,
        String code,
        String message,
        T data,
        List<ArtifactReference> artifacts) {

    /** 校验成功/失败状态一致性并防御性复制集合。 */
    public ToolResponse {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.TOOL_RESPONSE.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 ToolResponse schemaVersion: " + schemaVersion);
        }
        code = ContractChecks.requireNonBlank(code, "code");
        message = ContractChecks.requireNonBlank(message, "message");
        artifacts = ContractChecks.immutableList(artifacts, "artifacts");
        if (success && data == null) {
            throw new IllegalArgumentException("成功响应必须包含 data");
        }
        if (!success && data != null) {
            throw new IllegalArgumentException("失败响应不得包含 data");
        }
    }

    /** 创建成功响应。 */
    public static <T> ToolResponse<T> success(T data, List<ArtifactReference> artifacts) {
        return new ToolResponse<>(SchemaVersions.TOOL_RESPONSE, true, "OK", "Success", data, artifacts);
    }

    /** 创建不包含 data 的失败响应。 */
    public static <T> ToolResponse<T> failure(
            String code, String message, List<ArtifactReference> artifacts) {
        return new ToolResponse<>(SchemaVersions.TOOL_RESPONSE, false, code, message, null, artifacts);
    }
}
