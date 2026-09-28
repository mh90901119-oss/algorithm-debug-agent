package org.example.algorithmdebug.contracts.investigation;

/** 第一版冻结的确定性 Observation 操作符。 */
public enum ObservationOperator {
    METHOD_OBSERVED, RECORD_EXISTS, VALUE_EQUALS, VALUE_CHANGED,
    COUNT_COMPARE, PATH_CONTAINS, FAILURE_FINGERPRINT_MATCHES
}
