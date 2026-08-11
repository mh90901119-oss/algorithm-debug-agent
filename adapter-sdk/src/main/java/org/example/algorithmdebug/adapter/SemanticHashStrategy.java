package org.example.algorithmdebug.adapter;

/** 对调度结果执行领域相关的规范化和语义哈希。 */
@FunctionalInterface
public interface SemanticHashStrategy<T extends ScheduleResultSnapshot> {

    /**
     * 计算排除时间戳、运行 ID 和绝对路径等非业务噪声后的 SHA-256。
     *
     * @param snapshot 类型化调度结果
     * @return 64 位小写十六进制 SHA-256
     * @throws AdapterException 规范化或哈希失败
     */
    String semanticHash(T snapshot) throws AdapterException;
}

