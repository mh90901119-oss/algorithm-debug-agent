package org.example.algorithmdebug.contracts;

/** 一次 JDWP 采集的确定性完成分类。 */
public enum JdwpCollectionCompletion {
    SUCCESS,
    TRUNCATED,
    TARGET_FAILED,
    TOOL_FAILED,
    TIMED_OUT,
    AGENT_FAILED
}
