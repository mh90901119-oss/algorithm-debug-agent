package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;
import java.util.Optional;

/** CLI 严格解析后交给执行器的封闭命令集合。 */
public sealed interface CliCommand
        permits CliCommand.WorkspaceInit, CliCommand.ProjectRegister, CliCommand.Doctor,
        CliCommand.CaseOpen, CliCommand.CaseInspect, CliCommand.RunExecute,
        CliCommand.StaticAnalyze, CliCommand.CodePathPlanCreate,
        CliCommand.CodePathCollectionExecute, CliCommand.JdwpPlanCreate,
        CliCommand.JdwpCollectionExecute {

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

    /** 打开新 Case 或显式续接已有 Case；该命令本身不运行 UT。 */
    record CaseOpen(
            Path workspace,
            ProjectId projectId,
            TargetTest targetTest,
            Path questionFile,
            Optional<CaseId> caseId,
            Optional<String> adapterId) implements CliCommand {
        /** 校验解析后的 Case open 参数容器。 */
        public CaseOpen {
            require(workspace, "workspace");
            require(projectId, "projectId");
            require(targetTest, "targetTest");
            require(questionFile, "questionFile");
            require(caseId, "caseId");
            require(adapterId, "adapterId");
        }
    }

    /** 只读重建一个 Case 的有界 Digest。 */
    record CaseInspect(Path workspace, ProjectId projectId, CaseId caseId)
            implements CliCommand {
        /** 校验解析后的 Case inspect 参数。 */
        public CaseInspect {
            require(workspace, "workspace");
            require(projectId, "projectId");
            require(caseId, "caseId");
        }
    }

    /** 为已有 Analysis 显式执行一次新的目标 UT Run。 */
    record RunExecute(
            Path workspace,
            ProjectId projectId,
            CaseId caseId,
            AnalysisId analysisId) implements CliCommand {
        /** 校验解析后的 Run execute 参数。 */
        public RunExecute {
            require(workspace, "workspace");
            require(projectId, "projectId");
            require(caseId, "caseId");
            require(analysisId, "analysisId");
        }
    }

    /** 为已有 Analysis 生成并归档静态方法目录。 */
    record StaticAnalyze(
            Path workspace, ProjectId projectId, CaseId caseId, AnalysisId analysisId)
            implements CliCommand {
        /** 校验命令参数。 */
        public StaticAnalyze {
            require(workspace, "workspace"); require(projectId, "projectId");
            require(caseId, "caseId"); require(analysisId, "analysisId");
        }
    }

    /** 从有界 UTF-8 JSON 请求创建 CodePath 计划。 */
    record CodePathPlanCreate(
            Path workspace, ProjectId projectId, CaseId caseId, AnalysisId analysisId,
            Path requestFile) implements CliCommand {
        /** 校验命令参数。 */
        public CodePathPlanCreate {
            require(workspace, "workspace"); require(projectId, "projectId");
            require(caseId, "caseId"); require(analysisId, "analysisId");
            require(requestFile, "requestFile");
        }
    }

    /** 执行一个已归档的 CodePath 计划并创建新的 Collection。 */
    record CodePathCollectionExecute(
            Path workspace, ProjectId projectId, CaseId caseId, org.example.algorithmdebug.contracts.PlanId planId)
            implements CliCommand {
        /** 校验命令参数。 */
        public CodePathCollectionExecute {
            require(workspace, "workspace"); require(projectId, "projectId");
            require(caseId, "caseId"); require(planId, "planId");
        }
    }

    /** 从有界 UTF-8 JSON 请求创建 JDWP 计划。 */
    record JdwpPlanCreate(
            Path workspace, ProjectId projectId, CaseId caseId, AnalysisId analysisId,
            Path requestFile) implements CliCommand {
        /** 校验命令参数。 */
        public JdwpPlanCreate {
            require(workspace, "workspace"); require(projectId, "projectId");
            require(caseId, "caseId"); require(analysisId, "analysisId");
            require(requestFile, "requestFile");
        }
    }

    /** 执行一个已归档的 JDWP 计划并创建新的 Collection。 */
    record JdwpCollectionExecute(
            Path workspace, ProjectId projectId, CaseId caseId,
            org.example.algorithmdebug.contracts.PlanId planId) implements CliCommand {
        /** 校验命令参数。 */
        public JdwpCollectionExecute {
            require(workspace, "workspace"); require(projectId, "projectId");
            require(caseId, "caseId"); require(planId, "planId");
        }
    }

    private static void require(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}
