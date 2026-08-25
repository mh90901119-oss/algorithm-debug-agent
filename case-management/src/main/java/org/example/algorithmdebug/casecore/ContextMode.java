package org.example.algorithmdebug.casecore;

/** 调用方对已有 Case 的显式 Context 选择。 */
public enum ContextMode {
    /** 复用已有 Case 最新 Context。 */
    REUSE_LATEST,
    /** 在已有 Case 下追加新的 Context。 */
    CREATE_NEW
}
