package org.example.algorithmdebug.casecore;

/** LLM 或调用方可提出的结构化 Case 选择意图；最终结果仍由确定性规则裁决。 */
public enum CaseIntent {
    AUTO,
    FORCE_NEW_CASE,
    FORCE_REUSE,
    FORCE_NEW_REVISION
}
