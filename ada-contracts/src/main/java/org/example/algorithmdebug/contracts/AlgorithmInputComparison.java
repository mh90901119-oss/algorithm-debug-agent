package org.example.algorithmdebug.contracts;

/** 当前 Analysis 输入与同一 Case 最近一次成功归档输入的比较结果。 */
public enum AlgorithmInputComparison {
    /** Case 内没有更早的成功输入归档。 */
    FIRST_CAPTURE,
    /** 当前输入字节与最近一次输入完全一致。 */
    UNCHANGED,
    /** 当前输入字节与最近一次输入不同。 */
    CHANGED,
    /** 历史输入已损坏或无法校验，不能可靠比较。 */
    INCOMPARABLE
}
