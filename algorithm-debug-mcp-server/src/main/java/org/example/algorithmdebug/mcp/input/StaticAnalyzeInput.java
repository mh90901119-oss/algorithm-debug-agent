package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 生成静态方法目录的 Analysis 上下文。 */
public record StaticAnalyzeInput(CaseId caseId, AnalysisId analysisId)
        implements AnalysisScopedInput {
    public StaticAnalyzeInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
    }
}
