package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * Adapter、CLI 和编排层之间使用的统一工具响应。
 *
 * @param schemaVersion 响应 Schema 版本
 * @param success 是否成功
 * @param code 稳定的机器可读状态码
 * @param message 面向调用者的说明
 * @param data 成功数据；失败时必须为空
 * @param artifacts 本次调用产生的不可变产物引用
 * @param nextAllowedActions 基于当前状态允许执行的后续动作代码
 * @param <T> 成功数据类型
 */
public record ToolResponse<T>(
        String schemaVersion,
        boolean success,
        String code,
        String message,
        T data,
        List<ArtifactReference> artifacts,
        List<String> nextAllowedActions) {

    /** 校验成功/失败状态一致性并防御性复制集合。 */
    public ToolResponse {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.TOOL_RESPONSE.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 ToolResponse schemaVersion: " + schemaVersion);
        }
        code = ContractChecks.requireNonBlank(code, "code");
        message = ContractChecks.requireNonBlank(message, "message");
        artifacts = ContractChecks.immutableList(artifacts, "artifacts");
        nextAllowedActions = ContractChecks.immutableNonBlankStrings(
                nextAllowedActions, "nextAllowedActions");
        if (success && data == null) {
            throw new IllegalArgumentException("成功响应必须包含 data");
        }
        if (!success && data != null) {
            throw new IllegalArgumentException("失败响应不得包含 data");
        }
    }

    /**
     * 创建成功响应。
     *
     * @param data 成功数据
     * @param artifacts 产物引用
     * @param nextAllowedActions 后续允许动作
     * @param <T> 成功数据类型
     * @return 合法的成功响应
     */
    public static <T> ToolResponse<T> success(
            T data,
            List<ArtifactReference> artifacts,
            List<String> nextAllowedActions) {
        return new ToolResponse<>(
                SchemaVersions.TOOL_RESPONSE,
                true,
                "OK",
                "Success",
                data,
                artifacts,
                nextAllowedActions);
    }

    /**
     * 创建失败响应。
     *
     * @param code 机器可读错误码
     * @param message 错误说明
     * @param artifacts 失败前已产生的产物引用
     * @param nextAllowedActions 后续允许动作
     * @param <T> 调用成功时原本应返回的数据类型
     * @return 不包含 data 的失败响应
     */
    public static <T> ToolResponse<T> failure(
            String code,
            String message,
            List<ArtifactReference> artifacts,
            List<String> nextAllowedActions) {
        return new ToolResponse<>(
                SchemaVersions.TOOL_RESPONSE,
                false,
                code,
                message,
                null,
                artifacts,
                nextAllowedActions);
    }
}

