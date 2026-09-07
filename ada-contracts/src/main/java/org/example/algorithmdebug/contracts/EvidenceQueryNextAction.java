package org.example.algorithmdebug.contracts;

/** Evidence Query 结果要求模型执行的下一类动作。 */
public enum EvidenceQueryNextAction {
    REFINE_QUERY,
    INSPECT_MATCHED_RECORDS,
    NARROW_OR_PAGE,
    RECOLLECT_WITH_BETTER_SCOPE,
    TREAT_AS_SCOPED_ABSENCE,
    VERIFY_WITH_SOURCE
}
