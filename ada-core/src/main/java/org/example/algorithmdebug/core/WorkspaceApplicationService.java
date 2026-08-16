package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.WorkspaceInitializer;
import org.example.algorithmdebug.contracts.WorkspaceInitializationResult;

import java.nio.file.Path;

/** 执行 Workspace 初始化用例的薄应用服务。 */
public final class WorkspaceApplicationService {

    private final WorkspaceInitializer initializer;

    /**
     * 创建 Workspace 应用服务。
     *
     * @param initializer 已装配的领域初始化器
     */
    public WorkspaceApplicationService(WorkspaceInitializer initializer) {
        if (initializer == null) {
            throw new IllegalArgumentException("initializer 不能为空");
        }
        this.initializer = initializer;
    }

    /**
     * 初始化或验证外部 Workspace。
     *
     * @param root Workspace 根目录
     * @return 领域层原始初始化结果
     */
    public WorkspaceInitializationResult initialize(Path root) {
        return initializer.initialize(root);
    }
}
