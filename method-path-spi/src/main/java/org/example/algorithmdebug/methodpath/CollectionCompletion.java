package org.example.algorithmdebug.methodpath;

/** 外部方法路径采集的确定性完成状态。 */
public enum CollectionCompletion {
    SUCCESS,
    TRUNCATED,
    TARGET_FAILED,
    TOOL_FAILED,
    TIMED_OUT,
    AGENT_FAILED
}
