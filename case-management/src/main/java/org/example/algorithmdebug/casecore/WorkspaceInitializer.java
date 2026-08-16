package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.WorkspaceInitializationResult;
import org.example.algorithmdebug.contracts.WorkspaceManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** 幂等初始化或验证一个外部 Algorithm Debug Workspace。 */
public final class WorkspaceInitializer {

    private final WorkspaceManifestRepository manifestRepository;
    private final AtomicDocumentWriter writer;
    private final WorkspaceTemplateProvider templateProvider;
    private final Clock clock;

    /**
     * 创建 Workspace 初始化器。
     *
     * @param manifestRepository Manifest 仓储
     * @param writer create-new 原子写入器
     * @param templateProvider 固定配置模板提供器
     * @param clock 生成首次初始化时间的时钟
     */
    public WorkspaceInitializer(
            WorkspaceManifestRepository manifestRepository,
            AtomicDocumentWriter writer,
            WorkspaceTemplateProvider templateProvider,
            Clock clock) {
        if (manifestRepository == null || writer == null || templateProvider == null || clock == null) {
            throw new IllegalArgumentException("WorkspaceInitializer 依赖不能为空");
        }
        this.manifestRepository = manifestRepository;
        this.writer = writer;
        this.templateProvider = templateProvider;
        this.clock = clock;
    }

    /**
     * 创建缺失目录、Manifest 和模板；已有有效内容保持不变。
     *
     * @param root 外部 Workspace 根目录
     * @return 是否首次创建 Manifest 及规范化根路径
     */
    public WorkspaceInitializationResult initialize(Path root) {
        WorkspaceLayout layout = WorkspaceLayout.of(root);
        boolean created = manifestRepository.find(layout).isEmpty();
        createStandardDirectories(layout);
        if (created) {
            manifestRepository.create(layout, new WorkspaceManifest(
                    SchemaVersions.WORKSPACE_MANIFEST,
                    WorkspaceManifest.KIND,
                    clock.instant()));
        }
        createMissingTemplates(layout, templateProvider.templates());
        return new WorkspaceInitializationResult(
                layout.root().toString(), created, SchemaVersions.WORKSPACE_MANIFEST);
    }

    private static void createStandardDirectories(WorkspaceLayout layout) {
        for (Path directory : layout.standardDirectories()) {
            try {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new WorkspaceException("Workspace 标准路径不是目录: " + directory);
                }
            } catch (IOException | SecurityException failure) {
                throw new WorkspaceException("创建 Workspace 标准目录失败: " + directory, failure);
            }
        }
    }

    private void createMissingTemplates(WorkspaceLayout layout, Map<Path, byte[]> templates) {
        if (templates == null) {
            throw new WorkspaceException("Workspace 模板集合不能为空");
        }
        for (Map.Entry<Path, byte[]> entry : templates.entrySet()) {
            Path target = resolveTemplateTarget(layout, entry.getKey());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new WorkspaceException("Workspace 配置路径不是普通文件: " + target);
                }
                continue;
            }
            writer.writeNew(target, entry.getValue());
        }
    }

    private static Path resolveTemplateTarget(WorkspaceLayout layout, Path relativePath) {
        if (relativePath == null || relativePath.isAbsolute()) {
            throw new WorkspaceException("Workspace 模板路径必须是相对路径: " + relativePath);
        }
        Path configRoot = layout.configRoot();
        Path target = configRoot.resolve(relativePath).normalize();
        if (!target.startsWith(configRoot)) {
            throw new WorkspaceException("Workspace 模板路径越过 config 目录: " + relativePath);
        }
        return target;
    }
}
