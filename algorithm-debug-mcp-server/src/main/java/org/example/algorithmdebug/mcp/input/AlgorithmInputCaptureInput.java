package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 捕获目标 UT 算法输入的 Analysis 上下文。 */
public record AlgorithmInputCaptureInput(CaseId caseId, AnalysisId analysisId)
        implements AnalysisScopedInput {
    public AlgorithmInputCaptureInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
    }
}
