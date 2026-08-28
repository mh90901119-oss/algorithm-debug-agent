package org.example.algorithmdebug.contracts;

/**
 * 静态调用边的解析性质。直接边来自 javac 的声明目标；多态候选边仅表示当前源码范围内可能被
 * 动态分派到的具体覆盖方法，不能替代运行时证据。
 */
public enum CallResolutionKind {
    DIRECT,
    POLYMORPHIC_CANDIDATE
}
