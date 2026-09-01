package org.example.algorithmdebug.harness;

import java.nio.file.Path;

/**
 * 单个子进程输出流的有界归档结果。
 *
 * @param path 本地日志绝对路径
 * @param capturedBytes 实际落盘字节数
 * @param discardedBytes 超预算后仍已排空但未落盘的字节数
 * @param truncated 是否发生截断
 */
public record RunLog(
        Path path,
        long capturedBytes,
        long discardedBytes,
        boolean truncated) {

    /** 校验路径和计数不变量。 */
    public RunLog {
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("path must be an absolute path");
        }
        path = path.normalize();
        if (capturedBytes < 0 || discardedBytes < 0) {
            throw new IllegalArgumentException("Log byte counts must not be negative");
        }
        if (truncated != (discardedBytes > 0)) {
            throw new IllegalArgumentException("truncated must match discardedBytes");
        }
    }
}
