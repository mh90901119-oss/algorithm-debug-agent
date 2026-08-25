package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.casecore.ContextMode;

import java.nio.file.Path;
import java.util.Optional;

/** CLI 严格解析后交给执行器的封闭命令集合。 */
public sealed interface CliCommand
        permits CliCommand.WorkspaceInit, CliCommand.ProjectRegister, CliCommand.Doctor,
        CliCommand.CaseOpen, CliCommand.CaseInspect, CliCommand.RunExecute,
        CliCommand.StaticAnalyze, CliCommand.CodePathPlanCreate,
        CliCommand.CodePathCollectionExecute, CliCommand.JdwpPlanCreate,
        CliCommand.JdwpCollectionExecute, CliCommand.ArtifactRead,
        CliCommand.AnalysisComplete, CliCommand.CaseAudit, CliCommand.GanttInspect {

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
    record ProjectRegister(
            Path workspace,
            Path module,
            Optional<ProjectId> projectId,
            Optional<String> resultJsonDirectory)
            implements CliCommand {
        /** 校验命令参数。 */
        public ProjectRegister {
            require(workspace, "workspace");
            require(module, "module");
            require(projectId, "projectId");
            require(resultJsonDirectory, "resultJsonDirectory");
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
            Optional<String> adapterId,
            ContextMode contextMode) implements CliCommand {
        /** 校验解析后的 Case open 参数容器。 */
        public CaseOpen {
            require(workspace, "workspace");
            require(projectId, "projectId");
            require(targetTest, "targetTest");
            require(questionFile, "questionFile");
            require(caseId, "caseId");
            require(adapterId, "adapterId");
            require(contextMode, "contextMode");
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

    /** 只读审计一个 Case Workspace。 */
    record CaseAudit(Path workspace, ProjectId projectId, CaseId caseId) implements CliCommand {
        public CaseAudit { require(workspace, "workspace"); require(projectId, "projectId"); require(caseId, "caseId"); }
    }

    /** 通过 Artifact ID 有界读取 Gantt JSON。 */
    record GanttInspect(Path workspace, ProjectId projectId, CaseId caseId, String artifactId,
            String operation, String jsonPointer, int offset, int limit) implements CliCommand {
        public GanttInspect {
            require(workspace, "workspace"); require(projectId, "projectId"); require(caseId, "caseId");
            require(artifactId, "artifactId"); require(operation, "operation"); require(jsonPointer, "jsonPointer");
            if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Gantt inspection budget is invalid");
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

    /** 按注册 Artifact ID 读取一个有界 UTF-8 片段。 */
    record ArtifactRead(
            Path workspace, ProjectId projectId, CaseId caseId, String artifactId,
            long offsetBytes, int maxBytes) implements CliCommand {
        /** 校验解析后的 Artifact 读取参数。 */
        public ArtifactRead {
            require(workspace, "workspace"); require(projectId, "projectId");
            require(caseId, "caseId"); require(artifactId, "artifactId");
            if (offsetBytes < 0 || maxBytes < 1 || maxBytes > 65_536) {
                throw new IllegalArgumentException("Artifact read budget is invalid");
            }
        }
    }

    /** 从有界 JSON 文件原子完成一轮 Analysis。 */
    record AnalysisComplete(
            Path workspace, ProjectId projectId, CaseId caseId, AnalysisId analysisId,
            Path resultFile) implements CliCommand {
        /** 校验命令参数。 */
        public AnalysisComplete {
            require(workspace, "workspace"); require(projectId, "projectId");
            require(caseId, "caseId"); require(analysisId, "analysisId");
            require(resultFile, "resultFile");
        }
    }

    private static void require(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
