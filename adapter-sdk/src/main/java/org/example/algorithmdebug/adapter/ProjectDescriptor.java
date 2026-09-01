package org.example.algorithmdebug.adapter;

import org.example.algorithmdebug.contracts.ProjectId;

import java.nio.file.Path;

/**
 * Adapter 检查后得到的目标算法项目运行时描述。
 *
 * @param projectId 项目不透明 ID
 * @param displayName 项目显示名
 * @param projectRoot 绝对、规范化的项目根目录
 * @param buildTool 构建工具
 * @param buildFile 位于项目根目录内的绝对构建文件路径
 */
public record ProjectDescriptor(
        ProjectId projectId,
        String displayName,
        Path projectRoot,
        BuildTool buildTool,
        Path buildFile) {

    /** 规范化路径并阻止构建文件逃逸项目根目录。 */
    public ProjectDescriptor {
        projectId = AdapterChecks.requireNonNull(projectId, "projectId");
        displayName = AdapterChecks.requireNonBlank(displayName, "displayName");
        projectRoot = AdapterChecks.requireNonNull(projectRoot, "projectRoot");
        if (!projectRoot.isAbsolute()) {
            throw new IllegalArgumentException("projectRoot must be an absolute path");
        }
        projectRoot = projectRoot.normalize();
        buildTool = AdapterChecks.requireNonNull(buildTool, "buildTool");
        buildFile = AdapterChecks.requireNonNull(buildFile, "buildFile");
        buildFile = buildFile.isAbsolute()
                ? buildFile.normalize()
                : projectRoot.resolve(buildFile).normalize();
        if (!buildFile.startsWith(projectRoot) || buildFile.equals(projectRoot)) {
            throw new IllegalArgumentException("buildFile must be located inside projectRoot");
        }
    }
}

