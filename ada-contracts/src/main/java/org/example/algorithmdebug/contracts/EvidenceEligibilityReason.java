package org.example.algorithmdebug.contracts;

/**
 * 动态证据资格的稳定原因码。
 *
 * <p>原因码只描述确定性门禁结果，不承载模型推断，也不替代采集限制明细。</p>
 */
public enum EvidenceEligibilityReason {
    ARTIFACT_UNREADABLE,
    COLLECTION_INCOMPLETE,
    BASELINE_NOT_REQUIRED,
    FAILURE_FINGERPRINT_MATCHED,
    FAILURE_FINGERPRINT_CHANGED,
    FAILURE_FINGERPRINT_INCOMPARABLE,
    OBLIGATION_UNSATISFIED,
    LEGACY_UNKNOWN
}
