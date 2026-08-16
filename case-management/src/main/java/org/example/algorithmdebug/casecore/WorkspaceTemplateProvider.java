package org.example.algorithmdebug.casecore;

import java.nio.file.Path;
import java.util.Map;

/** 提供初始化 Workspace 所需的固定版本配置模板。 */
@FunctionalInterface
public interface WorkspaceTemplateProvider {

    /**
     * 返回相对 {@code config/} 的模板路径及完整内容。
     *
     * @return 不可修改的模板映射
     */
    Map<Path, byte[]> templates();
}
