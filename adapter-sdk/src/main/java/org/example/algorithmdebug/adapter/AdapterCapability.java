package org.example.algorithmdebug.adapter;

/** Adapter 可以向 Agent 声明的能力。 */
public enum AdapterCapability {
    /** 能够创建无采集基线 UT 启动规格。 */
    BASELINE_EXECUTION,
    /** 能够定位算法输入。 */
    INPUT_LOCATION,
    /** 能够定位并解析调度结果。 */
    SCHEDULE_RESULT,
    /** 能够计算排除非业务噪声后的语义哈希。 */
    SEMANTIC_HASH,
    /** 能够创建 Code Path Tracer 运行规格。 */
    CODE_PATH_COLLECTION,
    /** 能够创建 JDWP 调试运行规格。 */
    JDWP_COLLECTION
}

