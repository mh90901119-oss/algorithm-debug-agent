package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.algorithmdebug.contracts.ProjectId;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 按固定白名单和层级确定性合并 Workspace YAML 配置。 */
public final class WorkspaceConfigurationResolver {

    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final int MAX_TREE_DEPTH = 64;
    private static final Set<String> ALLOWED_DOCUMENTS = Set.of(
            "application",
            "execution",
            "collection-limits",
            "security-policy");

    private final BoundedDocumentMapper mapper;
    private final WorkspaceTemplateProvider defaultProvider;

    /**
     * 创建固定层级配置解析器。
     *
     * @param mapper 有界 YAML Mapper
     * @param defaultProvider 内置默认模板提供器
     */
    public WorkspaceConfigurationResolver(
            BoundedDocumentMapper mapper,
            WorkspaceTemplateProvider defaultProvider) {
        if (mapper == null || defaultProvider == null) {
            throw new IllegalArgumentException("mapper 和 defaultProvider 不能为空");
        }
        this.mapper = mapper;
        this.defaultProvider = defaultProvider;
    }

    /**
     * 按“内置、Workspace、项目、CLI”顺序合并一个白名单配置文档。
     *
     * <p>对象递归合并；标量和数组由高优先级层整体替换。文件层必须声明相同的当前 Schema，
     * CLI 覆盖不得修改 {@code schemaVersion}。</p>
     *
     * @param layout Workspace 布局
     * @param documentName 不含扩展名的白名单文档名
     * @param projectId 可选项目配置层
     * @param cliOverrides 当前命令声明的覆盖对象
     * @return 新建的合并配置树，不修改任何输入节点
     */
    public JsonNode resolve(
            WorkspaceLayout layout,
            String documentName,
            Optional<ProjectId> projectId,
            ObjectNode cliOverrides) {
        requireInputs(layout, documentName, projectId, cliOverrides);
        String fileName = documentName + ".yaml";
        ObjectNode resolved = readBuiltIn(fileName);
        mergeOptionalFile(resolved, layout.configRoot().resolve(fileName));
        projectId.ifPresent(id -> mergeOptionalFile(
                resolved,
                layout.projectConfigurationRoot(id).resolve(fileName)));
        if (cliOverrides.has("schemaVersion")) {
            throw configFailure("CLI 覆盖不得包含 schemaVersion", null);
        }
        validateTreeDepth(cliOverrides, 0);
        mergeObject(resolved, cliOverrides, 0);
        return resolved;
    }

    private ObjectNode readBuiltIn(String fileName) {
        Map<Path, byte[]> templates = defaultProvider.templates();
        if (templates == null) {
            throw configFailure("内置配置模板集合不能为空", null);
        }
        byte[] content = templates.get(Path.of(fileName));
        if (content == null) {
            throw configFailure("缺少内置配置模板: " + fileName, null);
        }
        try {
            return requireConfigurationObject(mapper.readYaml(content, JsonNode.class), "内置模板 " + fileName);
        } catch (WorkspaceException failure) {
            if ("CONFIG_INVALID".equals(failure.code())) {
                throw failure;
            }
            throw configFailure("内置配置模板无效: " + fileName, failure);
        }
    }

    private void mergeOptionalFile(ObjectNode resolved, Path path) {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw configFailure("配置路径不是普通文件: " + path, null);
        }
        try {
            ObjectNode layer = requireConfigurationObject(mapper.readYaml(path, JsonNode.class), path.toString());
            mergeObject(resolved, layer, 0);
        } catch (WorkspaceException failure) {
            if ("CONFIG_INVALID".equals(failure.code())) {
                throw failure;
            }
            throw configFailure("Workspace 配置无效: " + path, failure);
        }
    }

    private static ObjectNode requireConfigurationObject(JsonNode node, String source) {
        if (node == null || !node.isObject()) {
            throw configFailure("配置根节点必须是对象: " + source, null);
        }
        JsonNode schemaVersion = node.path("schemaVersion");
        if (!schemaVersion.isTextual() || !SUPPORTED_SCHEMA_VERSION.equals(schemaVersion.textValue())) {
            throw configFailure(
                    "配置 schemaVersion 不受支持: " + source + "，实际 " + schemaVersion,
                    null);
        }
        validateTreeDepth(node, 0);
        return ((ObjectNode) node).deepCopy();
    }

    private static void mergeObject(ObjectNode target, ObjectNode overlay, int depth) {
        if (depth > MAX_TREE_DEPTH) {
            throw configFailure("配置合并深度超过 " + MAX_TREE_DEPTH, null);
        }
        overlay.properties().forEach(entry -> {
            JsonNode existing = target.get(entry.getKey());
            JsonNode replacement = entry.getValue();
            if (existing != null && existing.isObject() && replacement.isObject()) {
                mergeObject((ObjectNode) existing, (ObjectNode) replacement, depth + 1);
            } else {
                target.set(entry.getKey(), replacement.deepCopy());
            }
        });
    }

    private static void validateTreeDepth(JsonNode node, int depth) {
        if (depth > MAX_TREE_DEPTH) {
            throw configFailure("配置树深度超过 " + MAX_TREE_DEPTH, null);
        }
        if (node.isContainerNode()) {
            node.forEach(child -> validateTreeDepth(child, depth + 1));
        }
    }

    private static void requireInputs(
            WorkspaceLayout layout,
            String documentName,
            Optional<ProjectId> projectId,
            ObjectNode cliOverrides) {
        if (layout == null || projectId == null || cliOverrides == null) {
            throw new IllegalArgumentException("layout、projectId 和 cliOverrides 不能为空");
        }
        if (documentName == null || !ALLOWED_DOCUMENTS.contains(documentName)) {
            throw configFailure("不支持的配置文档: " + documentName, null);
        }
    }

    private static WorkspaceException configFailure(String message, Throwable cause) {
        return cause == null
                ? new WorkspaceException("CONFIG_INVALID", message)
                : new WorkspaceException("CONFIG_INVALID", message, cause);
    }
}
