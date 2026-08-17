package org.example.algorithmdebug.contracts;

/**
 * Workspace 初始化命令的确定性结果。
 *
 * @param workspaceRoot Workspace 的规范化绝对路径
 * @param created 本次调用是否首次创建 Workspace
 * @param schemaVersion Workspace 清单 Schema 版本
 */
public record WorkspaceInitializationResult(
        String workspaceRoot,
        boolean created,
        String schemaVersion) {

    /** 校验必填路径和当前 Schema 版本。 */
    public WorkspaceInitializationResult {
        workspaceRoot = ContractChecks.requireNonBlank(workspaceRoot, "workspaceRoot");
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.WORKSPACE_MANIFEST.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "不支持的 WorkspaceInitializationResult schemaVersion: " + schemaVersion);
        }
    }
}
