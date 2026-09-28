package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.coordination.ActionInputLimits;

/** 有界读取已注册 Gantt Artifact。 */
public record GanttInspectInput(
        CaseId caseId,
        AnalysisId analysisId,
        String artifactId,
        String operation,
        String jsonPointer,
        int offset,
        int limit) implements AnalysisScopedInput {
    public GanttInspectInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        McpInputChecks.optionalText(
                artifactId, "artifactId", ActionInputLimits.MAX_ARTIFACT_ID_LENGTH)
                .orElseThrow();
        McpInputChecks.optionalText(
                operation, "operation", ActionInputLimits.MAX_GANTT_OPERATION_LENGTH)
                .orElseThrow();
        jsonPointer = jsonPointer == null ? "" : jsonPointer;
        if (!jsonPointer.equals(jsonPointer.strip())
                || jsonPointer.length() > ActionInputLimits.MAX_JSON_POINTER_LENGTH
                || offset < 0 || limit < 1 || limit > ActionInputLimits.MAX_GANTT_ROWS) {
            throw new IllegalArgumentException("Gantt input budget is invalid");
        }
    }
}
