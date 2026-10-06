package org.example.algorithmdebug.contracts.coordination;

/** 动作执行时需要的互斥与副作用等级。 */
public enum ActionSideEffect {
    READ_ONLY,
    CASE_WRITE,
    TARGET_EXECUTION
}
