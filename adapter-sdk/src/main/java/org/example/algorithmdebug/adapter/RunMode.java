package org.example.algorithmdebug.adapter;

/** 目标 UT 的运行模式。 */
public enum RunMode {
    /** 无动态采集的确定性基线运行。 */
    BASELINE,
    /** 使用外部 Code Path Tracer 的方法路径采集运行。 */
    CODE_PATH,
    /** 开启 JDWP 并由外部 Collector 采集的运行。 */
    JDWP
}

