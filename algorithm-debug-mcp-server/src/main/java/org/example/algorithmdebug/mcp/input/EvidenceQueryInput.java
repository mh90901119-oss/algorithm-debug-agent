package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceQueryRequest;
import org.example.algorithmdebug.contracts.coordination.ActionInputLimits;

/** 对已注册派生 Evidence 执行有界 typed 查询。 */
public record EvidenceQueryInput(
        CaseId caseId,
        AnalysisId analysisId,
        String artifactId,
        EvidenceQueryRequest request) implements AnalysisScopedInput {
    public EvidenceQueryInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        McpInputChecks.optionalText(
                artifactId, "artifactId", ActionInputLimits.MAX_ARTIFACT_ID_LENGTH)
                .orElseThrow();
        request = McpInputChecks.required(request, "request");
    }
}
