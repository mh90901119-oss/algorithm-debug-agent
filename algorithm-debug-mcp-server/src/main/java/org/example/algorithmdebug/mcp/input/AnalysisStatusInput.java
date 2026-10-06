package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 读取当前确定性 Analysis 控制视图。 */
public record AnalysisStatusInput(CaseId caseId, AnalysisId analysisId)
        implements AnalysisScopedInput {
    public AnalysisStatusInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
    }
}
