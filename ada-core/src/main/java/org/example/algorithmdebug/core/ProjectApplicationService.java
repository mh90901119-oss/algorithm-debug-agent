package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.ProjectRegistry;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistrationResult;

import java.nio.file.Path;
import java.util.Optional;

/** 执行 Maven 算法模块注册用例的薄应用服务。 */
public final class ProjectApplicationService {

    private final ProjectRegistry registry;

    /**
     * 创建项目注册应用服务。
     *
     * @param registry 已装配的项目注册领域服务
     */
    public ProjectApplicationService(ProjectRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry 不能为空");
        }
        this.registry = registry;
    }

    /**
     * 注册一个含独立 pom.xml 的算法模块。
     *
     * @param workspace 外部 Workspace 根目录
     * @param module 算法模块目录
     * @param projectId 可选显式 ProjectId
     * @return 领域层原始注册结果
     */
    public ProjectRegistrationResult register(
            Path workspace,
            Path module,
            Optional<ProjectId> projectId) {
        try {
            return registry.register(workspace, module, projectId);
        } catch (WorkspaceException failure) {
            throw new ControlPlaneException(failure.code(), "项目注册失败", failure);
        }
    }
}
