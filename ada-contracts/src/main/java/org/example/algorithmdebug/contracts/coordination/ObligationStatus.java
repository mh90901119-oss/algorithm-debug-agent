package org.example.algorithmdebug.contracts.coordination;

/** 确定性证据义务的当前评估状态。 */
public enum ObligationStatus {
    SATISFIED,
    MISSING,
    CONTRADICTED,
    UNCHECKABLE
}
