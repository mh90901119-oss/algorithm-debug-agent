package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;

import java.nio.file.Path;
import java.util.List;

/**
 * 计算外部 Agent Workspace 的标准目录，并强制所有派生路径留在根目录内。
 *
 * <p>该类只计算路径，不创建目录。文件系统变更由上层初始化与仓储组件负责。</p>
 */
public final class WorkspaceLayout {

    private final Path root;

    private WorkspaceLayout(Path root) {
        this.root = root;
    }

    /**
     * 从请求路径创建规范化的 Workspace 布局。
     *
     * @param root Workspace 根目录
     * @return 绝对且规范化的布局
     */
    public static WorkspaceLayout of(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Workspace root 不能为空");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new WorkspaceException(
                    "WORKSPACE_PATH_INVALID", "Workspace root 不能是文件系统根目录: " + normalized);
        }
        return new WorkspaceLayout(normalized);
    }

    /** @return Workspace 绝对规范化根目录 */
    public Path root() {
        return root;
    }

    /** @return 用户可编辑配置目录 */
    public Path configRoot() {
        return resolveWithinRoot(Path.of("config"));
    }

    /** @return 已注册项目及其 Case 数据根目录 */
    public Path projectsRoot() {
        return resolveWithinRoot(Path.of("projects"));
    }

    /** @return Agent 本机运行状态目录 */
    public Path systemRoot() {
        return resolveWithinRoot(Path.of("system"));
    }

    /**
     * 返回一个项目在 Agent Workspace 内的专属目录。
     *
     * @param projectId 不透明项目 ID，必须可作为单一路径段
     * @return 项目 Workspace 路径
     */
    public Path projectWorkspace(ProjectId projectId) {
        String segment = safeProjectSegment(projectId);
        return resolveWithinRoot(Path.of("projects", segment));
    }

    /**
     * 返回一个项目的 Case 归档根目录。
     *
     * @param projectId 不透明项目 ID
     * @return 项目 Case 根目录
     */
    public Path projectCases(ProjectId projectId) {
        String segment = safeProjectSegment(projectId);
        return resolveWithinRoot(Path.of("projects", segment, "cases"));
    }

    Path projectConfigurationRoot(ProjectId projectId) {
        String segment = safeProjectSegment(projectId);
        return resolveWithinRoot(Path.of("config", "projects", segment));
    }

    List<Path> standardDirectories() {
        return List.of(
                configRoot(),
                resolveWithinRoot(Path.of("config", "projects")),
                resolveWithinRoot(Path.of("knowledge", "shared")),
                projectsRoot(),
                systemRoot(),
                resolveWithinRoot(Path.of("system", "locks")),
                resolveWithinRoot(Path.of("system", "indexes")),
                resolveWithinRoot(Path.of("system", "logs")),
                resolveWithinRoot(Path.of("cache")),
                resolveWithinRoot(Path.of("temp")));
    }

    private Path resolveWithinRoot(Path relativePath) {
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("Workspace 子路径必须是相对路径: " + relativePath);
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Workspace 子路径越过根目录: " + relativePath);
        }
        return candidate;
    }

    private static String safeProjectSegment(ProjectId projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        String value = projectId.value();
        if (value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException("projectId 必须是单一安全路径段");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("projectId 不允许包含控制字符");
        }
        return value;
    }
}
