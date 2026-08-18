package org.example.algorithmdebug.contracts;

/**
 * 当前锁定 JDWP Collector 能够执行的有界快照参数。
 *
 * <p>{@code locals=true} 表示采集全部可见局部变量，并不表示变量白名单。P3 在 Collector
 * 支持投影前将对象深度和条目数限制在保守范围。</p>
 *
 * @param locals 是否采集全部可见局部变量
 * @param stack 是否采集调用栈
 * @param maxFrames 最大栈帧数
 * @param maxDepth 最大对象展开深度
 * @param maxItems 单个容器或对象最多保留条目数
 * @param maxStringLength 字符串最大字符数
 */
public record JdwpCaptureSpec(
        boolean locals,
        boolean stack,
        int maxFrames,
        int maxDepth,
        int maxItems,
        int maxStringLength) {

    /** 校验 Collector 能力和 P3 的保守硬限制。 */
    public JdwpCaptureSpec {
        if (!locals && !stack) {
            throw new IllegalArgumentException("JDWP capture 至少启用 locals 或 stack 之一");
        }
        if (maxFrames < 1 || maxFrames > 64
                || maxDepth < 0 || maxDepth > 2
                || maxItems < 1 || maxItems > 100
                || maxStringLength < 16 || maxStringLength > 1_024) {
            throw new IllegalArgumentException("JDWP capture 超出 P3 安全范围");
        }
    }

    /** 返回不读取局部变量的默认栈采集规格。 */
    public static JdwpCaptureSpec stackOnly() {
        return new JdwpCaptureSpec(false, true, 8, 1, 20, 256);
    }
}
