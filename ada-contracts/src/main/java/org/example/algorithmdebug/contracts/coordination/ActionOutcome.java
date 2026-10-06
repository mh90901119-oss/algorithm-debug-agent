package org.example.algorithmdebug.contracts.coordination;

/** 一次协调工具调用的领域结果；目标 UT 失败不等于工具调用失败。 */
public enum ActionOutcome {
    SUCCEEDED,
    REJECTED,
    FAILED
}
