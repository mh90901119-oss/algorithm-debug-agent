package org.example.algorithmdebug.contracts.investigation;

/** 调查状态、事件和绑定使用的稳定错误码。 */
public enum InvestigationErrorCode {
    INVESTIGATION_IDENTITY_MISMATCH,
    INVESTIGATION_LIMIT_EXCEEDED,
    INVESTIGATION_INVALID_TRANSITION,
    INVESTIGATION_JOURNAL_CONFLICT,
    INVESTIGATION_PREDICATE_NOT_FROZEN,
    INVESTIGATION_GAP_NOT_OPEN
}
