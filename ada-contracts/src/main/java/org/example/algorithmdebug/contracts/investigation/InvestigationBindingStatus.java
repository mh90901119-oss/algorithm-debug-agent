package org.example.algorithmdebug.contracts.investigation;

/** 采集计划是否携带可由确定性运行时执行的调查绑定。 */
public enum InvestigationBindingStatus {
    /** 当前版本计划，必须携带完整 {@link InvestigationBinding}。 */
    STRUCTURED,
    /** 旧版本自由文本意图的只读投影，不具备 Predicate 门禁效力。 */
    LEGACY_UNSTRUCTURED
}
