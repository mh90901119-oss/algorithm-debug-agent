package org.example.algorithmdebug.contracts;

/** 大模型可请求的通用证据维度，不包含具体算法业务概念。 */
public enum EvidenceDimension {
    TARGET_OUTCOME, INPUT, SOURCE, METHOD_PATH, RUNTIME_STATE, SCHEDULE_RESULT, VALIDATION
}
