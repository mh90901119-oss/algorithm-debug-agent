package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * JDWP Collector 可执行的有界快照参数。
 *
 * @param locals 是否采集顶层可见局部变量
 * @param stack 是否采集调用栈
 * @param maxFrames 最大栈帧数
 * @param maxDepth 最大对象展开深度
 * @param maxItems 单个对象或数组最多保留条目数
 * @param maxStringLength 字符串最多保留字符数
 * @param localNames 局部变量白名单；为空表示预算内全部可见变量
 * @param fieldPaths 从局部变量或 this 开始的字段路径白名单；为空表示预算内默认展开
 */
public record JdwpCaptureSpec(
        boolean locals,
        boolean stack,
        int maxFrames,
        int maxDepth,
        int maxItems,
        int maxStringLength,
        List<String> localNames,
        List<String> fieldPaths) {

    /** 保持 1.0 调用方兼容，空投影表示预算内默认采集。 */
    public JdwpCaptureSpec(
            boolean locals,
            boolean stack,
            int maxFrames,
            int maxDepth,
            int maxItems,
            int maxStringLength) {
        this(locals, stack, maxFrames, maxDepth, maxItems, maxStringLength, List.of(), List.of());
    }

    /** 校验当前阶段的保守硬限制。 */
    public JdwpCaptureSpec {
        if (!locals && !stack) {
            throw new IllegalArgumentException("JDWP capture must enable locals or stack");
        }
        if (maxFrames < 1 || maxFrames > 64
                || maxDepth < 0 || maxDepth > 2
                || maxItems < 1 || maxItems > 100
                || maxStringLength < 16 || maxStringLength > 1_024) {
            throw new IllegalArgumentException("JDWP capture exceeds the P3 safety limits");
        }
        localNames = ContractChecks.immutableBoundedStrings(
                localNames == null ? List.of() : localNames, "localNames", 256);
        fieldPaths = ContractChecks.immutableBoundedStrings(
                fieldPaths == null ? List.of() : fieldPaths, "fieldPaths", 2_048);
        if (localNames.size() > 64 || fieldPaths.size() > 128) {
            throw new IllegalArgumentException("JDWP capture projection exceeds the safety limits");
        }
    }

    /** 返回不读取局部变量的默认栈采集规格。 */
    public static JdwpCaptureSpec stackOnly() {
        return new JdwpCaptureSpec(false, true, 8, 1, 20, 256, List.of(), List.of());
    }
}
