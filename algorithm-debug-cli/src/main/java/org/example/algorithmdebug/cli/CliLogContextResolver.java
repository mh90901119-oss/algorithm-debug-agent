package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.casecore.logging.AgentLogContext;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.core.ArtifactBackedResult;
import org.example.algorithmdebug.core.MultiArtifactBackedResult;

/** 从已经解析的 CLI 命令和结果提取非敏感日志关联身份。 */
final class CliLogContextResolver {
    private CliLogContextResolver() { }

    static AgentLogContext before(CliCommand command) {
        if (command instanceof CliCommand.CaseOpen) {
            return AgentLogContext.bootstrap();
        }
        if (command instanceof CliCommand.CaseInspect value) return caseContext(value.workspace(), value.projectId(), value.caseId());
        if (command instanceof CliCommand.CaseAudit value) return caseContext(value.workspace(), value.projectId(), value.caseId());
        if (command instanceof CliCommand.GanttInspect value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withArtifact(value.artifactId());
        if (command instanceof CliCommand.RunExecute value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withAnalysis(value.analysisId());
        if (command instanceof CliCommand.AlgorithmInputCapture value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withAnalysis(value.analysisId());
        if (command instanceof CliCommand.StaticAnalyze value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withAnalysis(value.analysisId());
        if (command instanceof CliCommand.CodePathPlanCreate value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withAnalysis(value.analysisId());
        if (command instanceof CliCommand.JdwpPlanCreate value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withAnalysis(value.analysisId());
        if (command instanceof CliCommand.CodePathCollectionExecute value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withPlan(value.planId().value());
        if (command instanceof CliCommand.JdwpCollectionExecute value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withPlan(value.planId().value());
        if (command instanceof CliCommand.ArtifactRead value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withArtifact(value.artifactId());
        if (command instanceof CliCommand.AnalysisComplete value) return caseContext(value.workspace(), value.projectId(), value.caseId()).withAnalysis(value.analysisId());
        return AgentLogContext.bootstrap();
    }

    static AgentLogContext after(CliCommand command, Object result) {
        Object summary = result instanceof ArtifactBackedResult<?> value ? value.summary()
                : result instanceof MultiArtifactBackedResult<?> value ? value.summary() : result;
        if (command instanceof CliCommand.CaseOpen open && summary instanceof CaseOpenResult value) {
            return caseContext(open.workspace(), open.projectId(), value.caseId())
                    .withAnalysis(value.analysisId());
        }
        AgentLogContext context = before(command);
        if (summary instanceof RunOutcomeSummary value) {
            return context.withRun(value.runId().value());
        }
        if (summary instanceof CollectionExecutionSummary value) {
            return context.withAnalysis(value.analysisId())
                    .withRun(value.runId().value())
                    .withCollection(value.collectionId().value());
        }
        return context;
    }

    static String commandName(CliCommand command) {
        return command == null ? "unknown" : command.getClass().getSimpleName();
    }

    private static AgentLogContext caseContext(
            java.nio.file.Path workspace,
            org.example.algorithmdebug.contracts.ProjectId projectId,
            org.example.algorithmdebug.contracts.CaseId caseId) {
        return AgentLogContext.forCase(workspace, projectId, caseId);
    }
}
