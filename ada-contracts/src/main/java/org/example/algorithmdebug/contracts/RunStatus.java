package org.example.algorithmdebug.contracts;

/** 一次目标 UT 进程执行的终态或进行中状态。 */
public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    ABORTED
}
