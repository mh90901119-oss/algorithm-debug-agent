package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.plan.JdwpPlanRequest;

/** 编译并归档 JDWP Plan 的 typed 输入。 */
public record JdwpPlanCreateInput(
        CaseId caseId, AnalysisId analysisId, JdwpPlanRequest request)
        implements AnalysisScopedInput {
    public JdwpPlanCreateInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        request = McpInputChecks.required(request, "request");
    }
}
