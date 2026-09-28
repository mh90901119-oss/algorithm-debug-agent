package org.example.algorithmdebug.mcp.input;

import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryLimits;
import org.example.algorithmdebug.contracts.investigation.SourceQueryMode;

/** 六种确定性 Source Query 的 typed MCP 输入。 */
public record SourceQueryInput(
        CaseId caseId,
        AnalysisId analysisId,
        SourceQueryMode mode,
        String methodKey,
        String targetMethodKey,
        SourceAnchor sourceAnchor,
        String symbol,
        SourceQueryBudget budget) implements AnalysisScopedInput {
    public SourceQueryInput {
        caseId = McpInputChecks.required(caseId, "caseId");
        analysisId = McpInputChecks.required(analysisId, "analysisId");
        mode = McpInputChecks.required(mode, "mode");
        McpInputChecks.optionalText(
                methodKey, "methodKey", SourceQueryLimits.MAX_METHOD_KEY_LENGTH);
        McpInputChecks.optionalText(
                targetMethodKey, "targetMethodKey", SourceQueryLimits.MAX_METHOD_KEY_LENGTH);
        McpInputChecks.optionalText(symbol, "symbol", SourceQueryLimits.MAX_SYMBOL_LENGTH);
        budget = budget == null ? SourceQueryBudget.defaults() : budget;
    }

    public Optional<String> optionalMethodKey() {
        return McpInputChecks.optionalText(
                methodKey, "methodKey", SourceQueryLimits.MAX_METHOD_KEY_LENGTH);
    }

    public Optional<String> optionalTargetMethodKey() {
        return McpInputChecks.optionalText(
                targetMethodKey, "targetMethodKey", SourceQueryLimits.MAX_METHOD_KEY_LENGTH);
    }

    public Optional<SourceAnchor> optionalSourceAnchor() {
        return Optional.ofNullable(sourceAnchor);
    }

    public Optional<String> optionalSymbol() {
        return McpInputChecks.optionalText(symbol, "symbol", SourceQueryLimits.MAX_SYMBOL_LENGTH);
    }
}
