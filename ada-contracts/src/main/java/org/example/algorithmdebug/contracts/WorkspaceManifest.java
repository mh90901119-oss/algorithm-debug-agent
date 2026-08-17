package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * 标识一个由 Algorithm Debug Agent 管理的外部 Workspace。
 *
 * @param schemaVersion 清单 Schema 版本
 * @param kind 固定的 Workspace 类型标识
 * @param createdAt Workspace 首次初始化时间
 */
public record WorkspaceManifest(
        String schemaVersion,
        String kind,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {

    /** Workspace 清单的固定类型标识。 */
    public static final String KIND = "ALGORITHM_DEBUG_WORKSPACE";

    /** 校验当前版本、类型和创建时间。 */
    public WorkspaceManifest {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.WORKSPACE_MANIFEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 WorkspaceManifest schemaVersion: " + schemaVersion);
        }
        kind = ContractChecks.requireNonBlank(kind, "kind");
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("不支持的 Workspace 类型: " + kind);
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
