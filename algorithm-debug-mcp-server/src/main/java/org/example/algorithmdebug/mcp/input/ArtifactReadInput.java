package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.coordination.ActionInputLimits;

/** 有界读取已注册 Artifact 文本片段。 */
public record ArtifactReadInput(
        CaseId caseId,
        AnalysisId analysisId,
        String artifactId,
        long offsetBytes,
        int maxBytes) implements AnalysisScopedInput {
    public ArtifactReadInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        McpInputChecks.optionalText(
                artifactId, "artifactId", ActionInputLimits.MAX_ARTIFACT_ID_LENGTH)
                .orElseThrow();
        if (offsetBytes < 0 || maxBytes < 1
                || maxBytes > ActionInputLimits.MAX_ARTIFACT_READ_BYTES) {
            throw new IllegalArgumentException("Artifact read budget is invalid");
        }
    }
}
