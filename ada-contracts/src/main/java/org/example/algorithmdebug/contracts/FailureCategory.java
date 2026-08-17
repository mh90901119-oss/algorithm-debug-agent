package org.example.algorithmdebug.contracts;

/** 目标测试失败的粗粒度阶段分类，不表达算法业务根因。 */
public enum FailureCategory {
    BUILD_FAILURE,
    TEST_FAILURE,
    TEST_ERROR,
    TEST_NOT_EXECUTED,
    UNKNOWN
}
