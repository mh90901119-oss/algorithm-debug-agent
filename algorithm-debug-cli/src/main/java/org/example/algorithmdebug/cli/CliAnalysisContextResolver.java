package org.example.algorithmdebug.cli;

import java.nio.file.Path;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.CaseDigestReader;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.core.CaseRunException;

/** 从不可变 typed 控制文档解析旧 CLI 未携带的 Analysis 归属，不执行业务动作。 */
final class CliAnalysisContextResolver
        implements CliActionRequestMapper.AnalysisContextResolver {
    private final WorkspaceLayout workspace;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final ProjectRegistrationRepository projects;

    /** @param workspaceRoot 当前单次 CLI 命令的 Workspace 根 */
    CliAnalysisContextResolver(Path workspaceRoot) {
        this.workspace = WorkspaceLayout.of(workspaceRoot);
        this.mapper = new BoundedDocumentMapper();
        this.writer = new AtomicDocumentWriter();
        this.projects = new ProjectRegistrationRepository(mapper, writer);
    }

    @Override
    public AnalysisId latestAnalysis(ProjectId projectId, CaseId caseId) {
        try {
            CaseArchiveRepository archive = archive(projectId);
            return new CaseDigestReader(archive).read(caseId).latestAnalysisId()
                    .orElseThrow(() -> new CaseRunException(
                            "ANALYSIS_NOT_FOUND", "Case has no Analysis"));
        } catch (WorkspaceException failure) {
            throw archiveFailure(failure);
        }
    }

    @Override
    public AnalysisId codePathPlanAnalysis(
            ProjectId projectId, CaseId caseId, PlanId planId) {
        try {
            return archive(projectId).requireCodePathPlan(caseId, planId).analysisId();
        } catch (WorkspaceException failure) {
            throw archiveFailure(failure);
        }
    }

    @Override
    public AnalysisId jdwpPlanAnalysis(
            ProjectId projectId, CaseId caseId, PlanId planId) {
        try {
            return archive(projectId).requireJdwpPlan(caseId, planId).analysisId();
        } catch (WorkspaceException failure) {
            throw archiveFailure(failure);
        }
    }

    /** 供只测试 Analysis Begin 的 mapper 使用；若意外解析既有上下文则立即失败。 */
    static CliActionRequestMapper.AnalysisContextResolver unsupported() {
        return new CliActionRequestMapper.AnalysisContextResolver() {
            @Override
            public AnalysisId latestAnalysis(ProjectId projectId, CaseId caseId) {
                throw new IllegalStateException("Analysis context resolution is not expected");
            }

            @Override
            public AnalysisId codePathPlanAnalysis(
                    ProjectId projectId, CaseId caseId, PlanId planId) {
                throw new IllegalStateException("CodePath Plan resolution is not expected");
            }

            @Override
            public AnalysisId jdwpPlanAnalysis(
                    ProjectId projectId, CaseId caseId, PlanId planId) {
                throw new IllegalStateException("JDWP Plan resolution is not expected");
            }
        };
    }

    private CaseArchiveRepository archive(ProjectId projectId) {
        if (projects.findById(workspace, projectId).isEmpty()) {
            throw new CaseRunException("PROJECT_NOT_REGISTERED", "Project is not registered");
        }
        return new CaseArchiveRepository(workspace.projectCases(projectId), mapper, writer);
    }

    private static CaseRunException archiveFailure(WorkspaceException failure) {
        return new CaseRunException(failure.code(), "CLI analysis context resolution failed", failure);
    }
}
