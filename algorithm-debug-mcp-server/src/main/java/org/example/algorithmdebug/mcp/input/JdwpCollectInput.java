package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;

/** 执行已归档 JDWP Plan。 */
public record JdwpCollectInput(CaseId caseId, AnalysisId analysisId, PlanId planId)
        implements AnalysisScopedInput {
    public JdwpCollectInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        planId = McpInputChecks.required(planId, "planId");
    }
}
