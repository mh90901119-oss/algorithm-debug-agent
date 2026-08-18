package org.example.algorithmdebug.contracts;

/** JDWP 执行已达到的最后一个确定性阶段。 */
public enum JdwpCollectionStage {
    REQUEST_ARCHIVED,
    SOURCE_VALIDATED,
    TARGET_STARTED,
    TARGET_READY,
    COLLECTOR_STARTED,
    ATTACHED_OR_RESUMED,
    PROCESS_COMPLETED,
    BASELINE_CHECKED,
    FAILED
}
