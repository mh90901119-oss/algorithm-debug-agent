package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 执行目标 UT 的 Analysis 上下文。 */
public record RunTestInput(CaseId caseId, AnalysisId analysisId)
        implements AnalysisScopedInput {
    public RunTestInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
    }
}
