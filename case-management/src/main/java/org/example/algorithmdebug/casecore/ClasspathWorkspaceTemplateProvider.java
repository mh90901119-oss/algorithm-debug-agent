package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从 Agent JAR 内读取四个明确命名的 Workspace 配置模板。 */
public final class ClasspathWorkspaceTemplateProvider implements WorkspaceTemplateProvider {

    private static final String RESOURCE_ROOT =
            "/org/example/algorithmdebug/casecore/workspace-templates/";
    private static final List<String> TEMPLATE_NAMES = List.of(
            "application.yaml",
            "execution.yaml",
            "collection-limits.yaml",
            "security-policy.yaml");

    /**
     * 逐个读取固定资源，不扫描 classpath 目录。
     *
     * @return 相对配置路径到有界模板字节的不可修改映射
     */
    @Override
    public Map<Path, byte[]> templates() {
        Map<Path, byte[]> templates = new LinkedHashMap<>();
        for (String name : TEMPLATE_NAMES) {
            templates.put(Path.of(name), readTemplate(name));
        }
        return Map.copyOf(templates);
    }

    private static byte[] readTemplate(String name) {
        String resourceName = RESOURCE_ROOT + name;
        try (InputStream input = ClasspathWorkspaceTemplateProvider.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new WorkspaceException("缺少内置 Workspace 模板: " + resourceName);
            }
            byte[] content = input.readNBytes(BoundedDocumentMapper.MAX_DOCUMENT_BYTES + 1);
            if (content.length > BoundedDocumentMapper.MAX_DOCUMENT_BYTES) {
                throw new WorkspaceException(
                        "内置 Workspace 模板超过最大字节数 "
                                + BoundedDocumentMapper.MAX_DOCUMENT_BYTES + ": " + resourceName);
            }
            return content;
        } catch (IOException failure) {
            throw new WorkspaceException("读取内置 Workspace 模板失败: " + resourceName, failure);
        }
    }
}
