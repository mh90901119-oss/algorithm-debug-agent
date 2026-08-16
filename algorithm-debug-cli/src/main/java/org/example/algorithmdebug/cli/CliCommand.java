package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.ProjectId;

import java.nio.file.Path;
import java.util.Optional;

/** CLI 严格解析后交给执行器的封闭命令集合。 */
public sealed interface CliCommand
        permits CliCommand.WorkspaceInit, CliCommand.ProjectRegister, CliCommand.Doctor {

    /**
     * 初始化外部 Workspace。
     *
     * @param root Workspace 根目录
     */
    record WorkspaceInit(Path root) implements CliCommand {
        /** 校验命令参数。 */
        public WorkspaceInit {
            require(root, "root");
        }
    }

    /**
     * 登记 Maven 算法模块。
     *
     * @param workspace 外部 Workspace 根目录
     * @param module 算法模块目录
     * @param projectId 可选显式 ProjectId
     */
    record ProjectRegister(Path workspace, Path module, Optional<ProjectId> projectId)
            implements CliCommand {
        /** 校验命令参数。 */
        public ProjectRegister {
            require(workspace, "workspace");
            require(module, "module");
            require(projectId, "projectId");
        }
    }

    /**
     * 诊断本机环境和可选算法模块。
     *
     * @param workspace 外部 Workspace 根目录
     * @param module 可选算法模块目录
     */
    record Doctor(Path workspace, Optional<Path> module) implements CliCommand {
        /** 校验命令参数。 */
        public Doctor {
            require(workspace, "workspace");
            require(module, "module");
        }
    }

    private static void require(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}
