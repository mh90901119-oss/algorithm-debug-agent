package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.investigation.InvestigationUpdateCommand;

/** 追加一种允许的调查命令，不暴露状态或 Evaluation 写入口。 */
public record InvestigationUpdateInput(InvestigationUpdateCommand command)
        implements AnalysisScopedInput {
    public InvestigationUpdateInput {
        command = McpInputChecks.required(command, "command");
    }

    @Override
    public CaseId caseId() {
        return command.caseId();
    }

    @Override
    public AnalysisId analysisId() {
        return command.analysisId();
    }
}
