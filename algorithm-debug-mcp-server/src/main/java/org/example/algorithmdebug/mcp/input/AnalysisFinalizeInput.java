package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;

/** 提交待确定性 Gate 校验的结论候选。 */
public record AnalysisFinalizeInput(
        CaseId caseId, AnalysisId analysisId, ConclusionCandidate candidate)
        implements AnalysisScopedInput {
    public AnalysisFinalizeInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        candidate = McpInputChecks.required(candidate, "candidate");
        if (!caseId.equals(candidate.identity().caseId())
                || !analysisId.equals(candidate.identity().analysisId())) {
            throw new IllegalArgumentException("Conclusion candidate identity mismatch");
        }
    }
}
