package org.example.algorithmdebug.mcp.input;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 提供 MCP 输入所属的 Case/Analysis 身份，Project 只能来自冻结的 Server 上下文。 */
public interface AnalysisScopedInput {
    /** @return Case 身份 */
    CaseId caseId();

    /** @return Analysis 身份 */
    AnalysisId analysisId();
}
