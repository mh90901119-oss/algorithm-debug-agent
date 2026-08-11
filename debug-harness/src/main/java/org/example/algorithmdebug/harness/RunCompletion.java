package org.example.algorithmdebug.harness;

/** 一次目标测试子进程的确定性完成分类。 */
public enum RunCompletion {
    /** 子进程正常退出且退出码为 0。 */
    SUCCEEDED,
    /** 子进程正常退出但退出码非 0。 */
    FAILED,
    /** 达到运行超时并进入进程树清理。 */
    TIMED_OUT
}
