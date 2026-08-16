package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.WorkspaceManifest;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** 原子创建和读取 Workspace 根目录中的版本化清单。 */
public final class WorkspaceManifestRepository {

    private static final String MANIFEST_FILE_NAME = "workspace.yaml";

    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;

    /**
     * 创建 Workspace Manifest 仓储。
     *
     * @param mapper 有界 YAML/JSON Mapper
     * @param writer create-new 原子写入器
     */
    public WorkspaceManifestRepository(BoundedDocumentMapper mapper, AtomicDocumentWriter writer) {
        if (mapper == null || writer == null) {
            throw new IllegalArgumentException("mapper 和 writer 不能为空");
        }
        this.mapper = mapper;
        this.writer = writer;
    }

    /**
     * 查找并验证 Workspace Manifest。
     *
     * @param layout Workspace 布局
     * @return 不存在时为空，存在时为已校验 Manifest
     */
    public Optional<WorkspaceManifest> find(WorkspaceLayout layout) {
        Path path = manifestPath(layout);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "WORKSPACE_MANIFEST_INVALID", "Workspace Manifest 不是普通文件: " + path);
        }
        return Optional.of(readManifest(path));
    }

    /**
     * 原子创建 Workspace Manifest，已有文件绝不覆盖。
     *
     * @param layout Workspace 布局
     * @param manifest 待保存的当前版本 Manifest
     */
    public void create(WorkspaceLayout layout, WorkspaceManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest 不能为空");
        }
        writer.writeNew(manifestPath(layout), mapper.writeYaml(manifest));
    }

    /**
     * 读取必需的 Workspace Manifest。
     *
     * @param layout Workspace 布局
     * @return 已校验 Manifest
     * @throws WorkspaceException Manifest 不存在或无效
     */
    public WorkspaceManifest require(WorkspaceLayout layout) {
        return find(layout).orElseThrow(
                () -> new WorkspaceException(
                        "WORKSPACE_MANIFEST_INVALID", "Workspace Manifest 不存在: " + manifestPath(layout)));
    }

    private static Path manifestPath(WorkspaceLayout layout) {
        if (layout == null) {
            throw new IllegalArgumentException("layout 不能为空");
        }
        return layout.root().resolve(MANIFEST_FILE_NAME).normalize();
    }

    private WorkspaceManifest readManifest(Path path) {
        JsonNode tree;
        try {
            tree = mapper.readYaml(path, JsonNode.class);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "WORKSPACE_MANIFEST_INVALID",
                    "Workspace Manifest 无效: " + failure.getMessage(),
                    failure);
        }
        if (tree == null || !tree.isObject()) {
            throw new WorkspaceException("WORKSPACE_MANIFEST_INVALID", "Workspace Manifest 根节点必须是对象");
        }
        JsonNode schemaVersion = tree.path("schemaVersion");
        if (!schemaVersion.isTextual()) {
            throw new WorkspaceException(
                    "WORKSPACE_MANIFEST_INVALID", "Workspace Manifest 缺少文本 schemaVersion");
        }
        if (!SchemaVersions.WORKSPACE_MANIFEST.equals(schemaVersion.textValue())) {
            IllegalArgumentException cause = new IllegalArgumentException(
                    "不支持的 Workspace Manifest schemaVersion: " + schemaVersion.textValue());
            throw new WorkspaceException(
                    "WORKSPACE_SCHEMA_UNSUPPORTED", "Workspace Manifest Schema 版本不受支持", cause);
        }
        try {
            return mapper.convertJsonTree(tree, WorkspaceManifest.class);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "WORKSPACE_MANIFEST_INVALID", "Workspace Manifest 字段无效", failure);
        }
    }
}
