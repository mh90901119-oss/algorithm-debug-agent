package org.example.algorithmdebug.contracts.coordination;

/** 证据义务的来源和用途。 */
public enum EvidenceObligationKind {
    SYSTEM_PREREQUISITE,
    ACTION_PRECONDITION,
    ACTION_POSTCONDITION,
    INVESTIGATION_PREDICATE,
    CONCLUSION_GATE
}
