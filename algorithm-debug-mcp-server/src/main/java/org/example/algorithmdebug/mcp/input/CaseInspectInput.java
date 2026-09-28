package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 读取 Case 摘要的显式 Analysis 上下文。 */
public record CaseInspectInput(CaseId caseId, AnalysisId analysisId)
        implements AnalysisScopedInput {
    public CaseInspectInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
    }
}
