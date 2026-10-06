package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 审计 Case 归档的显式 Analysis 上下文。 */
public record CaseAuditInput(CaseId caseId, AnalysisId analysisId)
        implements AnalysisScopedInput {
    public CaseAuditInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
    }
}
