package org.example.algorithmdebug.contracts.coordination;

/** Canonical MCP Catalog 中可由模型请求的基线分析动作。 */
public enum AnalysisActionType {
    ANALYSIS_BEGIN,
    CASE_INSPECT,
    ALGORITHM_INPUT_CAPTURE,
    CASE_AUDIT,
    GANTT_INSPECT,
    RUN_TEST,
    STATIC_ANALYZE,
    SOURCE_QUERY,
    INVESTIGATION_UPDATE,
    CODEPATH_PLAN_CREATE,
    CODEPATH_COLLECT,
    JDWP_PLAN_CREATE,
    JDWP_COLLECT,
    ARTIFACT_READ,
    EVIDENCE_QUERY,
    ANALYSIS_STATUS,
    ANALYSIS_FINALIZE
}
