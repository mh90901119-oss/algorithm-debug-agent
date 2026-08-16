package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.core.DoctorApplicationService;
import org.example.algorithmdebug.core.ProjectApplicationService;
import org.example.algorithmdebug.core.WorkspaceApplicationService;

import java.util.Optional;

/** 将 CLI 命令映射到 Core 用例，不处理序列化或文件布局。 */
public final class CliCommandExecutor {

    private final WorkspaceApplicationService workspaceService;
    private final ProjectApplicationService projectService;
    private final DoctorApplicationService doctorService;

    /**
     * 创建 CLI 命令执行器。
     *
     * @param workspaceService Workspace Core 服务
     * @param projectService Project Core 服务
     * @param doctorService Doctor Core 服务
     */
    public CliCommandExecutor(
            WorkspaceApplicationService workspaceService,
            ProjectApplicationService projectService,
            DoctorApplicationService doctorService) {
        if (workspaceService == null || projectService == null || doctorService == null) {
            throw new IllegalArgumentException("CLI Core 服务不能为空");
        }
        this.workspaceService = workspaceService;
        this.projectService = projectService;
        this.doctorService = doctorService;
    }

    /**
     * 执行命令并返回对应的版本化结果 DTO。
     *
     * @param command 已解析命令
     * @return Workspace、Project 或 Doctor 结果
     */
    public Object execute(CliCommand command) {
        if (command instanceof CliCommand.WorkspaceInit workspaceInit) {
            return workspaceService.initialize(workspaceInit.root());
        }
        if (command instanceof CliCommand.ProjectRegister projectRegister) {
            return projectService.register(
                    projectRegister.workspace(), projectRegister.module(), projectRegister.projectId());
        }
        if (command instanceof CliCommand.Doctor doctor) {
            return doctorService.diagnose(doctor.workspace(), doctor.module(), Optional.empty());
        }
        throw new IllegalArgumentException("不支持的 CLI 命令类型");
    }
}
