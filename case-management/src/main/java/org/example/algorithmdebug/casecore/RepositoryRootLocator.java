package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 只沿算法模块父目录链定位最近的 Git 仓库根目录。 */
public final class RepositoryRootLocator {

    /**
     * 定位模块所属仓库；没有 Git 标记时退化为模块自身。
     *
     * @param moduleRoot 已存在的算法模块目录
     * @return 规范化真实仓库根目录
     */
    public Path locate(Path moduleRoot) {
        if (moduleRoot == null) {
            throw new IllegalArgumentException("moduleRoot 不能为空");
        }
        try {
            Path canonicalModule = moduleRoot.toRealPath();
            if (!Files.isDirectory(canonicalModule, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException("算法模块路径不是目录: " + canonicalModule);
            }
            for (Path current = canonicalModule; current != null; current = current.getParent()) {
                Path marker = current.resolve(".git");
                if (Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)
                        || Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    return current;
                }
            }
            return canonicalModule;
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("无法规范化算法模块路径: " + moduleRoot, failure);
        }
    }
}
