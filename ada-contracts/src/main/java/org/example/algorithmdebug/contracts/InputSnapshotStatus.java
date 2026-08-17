package org.example.algorithmdebug.contracts;

/** 目标 UT 算法输入在创建 Context 时的可观察状态。 */
public enum InputSnapshotStatus {
    PRESENT,
    MISSING,
    NOT_APPLICABLE,
    UNRESOLVED
}
