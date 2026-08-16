package org.example.algorithmdebug.contracts;

/**
 * 算法模块有界源码范围的内容摘要。
 *
 * @param sha256 规范化文件元组的 SHA-256；不完整时为已观察部分的摘要
 * @param fileCount 已参与摘要的普通文件数
 * @param totalBytes 已参与摘要的文件总字节数
 * @param completeness 是否完整覆盖约定扫描范围
 */
public record SourceSnapshot(
        String sha256,
        int fileCount,
        long totalBytes,
        SnapshotCompleteness completeness) {

    /** 校验 Hash、计数和完整性。 */
    public SourceSnapshot {
        sha256 = ContractChecks.requireSha256(sha256, "sha256");
        completeness = ContractChecks.requireNonNull(completeness, "completeness");
        if (fileCount < 0 || totalBytes < 0) {
            throw new IllegalArgumentException("源码快照计数不能为负数");
        }
    }
}
