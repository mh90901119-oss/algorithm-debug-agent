package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.plan.CodePathPlanRequest;

/** 编译并归档 CodePath Plan 的 typed 输入。 */
public record CodePathPlanCreateInput(
        CaseId caseId, AnalysisId analysisId, CodePathPlanRequest request)
        implements AnalysisScopedInput {
    public CodePathPlanCreateInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        request = McpInputChecks.required(request, "request");
    }
}
