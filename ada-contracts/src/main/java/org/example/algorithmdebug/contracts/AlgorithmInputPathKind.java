package org.example.algorithmdebug.contracts;

/** 目标 UT 中算法输入路径字面量的解析方式。 */
public enum AlgorithmInputPathKind {
    /** 相对目标 Maven 模块根目录解析。 */
    RELATIVE,
    /** 使用 UT 中声明的绝对路径。 */
    ABSOLUTE
}
