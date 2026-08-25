package org.example.algorithmdebug.harness;

/** 等待受管进程输出标记的确定性结果。 */
public enum ProcessOutputWaitResult {
    OBSERVED,
    PROCESS_EXITED,
    TIMED_OUT
}
