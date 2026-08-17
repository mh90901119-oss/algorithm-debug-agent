package org.example.algorithmdebug.contracts;

/**
 * Adapter 可定位算法输入的状态与内容摘要。
 *
 * @param status 输入状态
 * @param relativePath 相对算法模块的可移植路径；无法确定时为空字符串
 * @param sha256 输入存在时的内容 SHA-256，否则为空字符串
 * @param sizeBytes 输入存在时的字节数，否则为零
 * @param diagnostic 缺失或无法定位时的有界稳定说明
 */
public record InputSnapshot(
        InputSnapshotStatus status,
        String relativePath,
        String sha256,
        long sizeBytes,
        String diagnostic) {

    /** 校验输入状态与路径、Hash、大小的一致性。 */
    public InputSnapshot {
        status = ContractChecks.requireNonNull(status, "status");
        relativePath = checkedOptionalPath(relativePath);
        diagnostic = ContractChecks.requireBoundedText(diagnostic, "diagnostic", 2_048, true);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数");
        }
        if (status == InputSnapshotStatus.PRESENT) {
            if (relativePath.isEmpty()) {
                throw new IllegalArgumentException("PRESENT 输入必须包含 relativePath");
            }
            sha256 = ContractChecks.requireSha256(sha256, "sha256");
        } else {
            if (sha256 == null || !sha256.isEmpty() || sizeBytes != 0) {
                throw new IllegalArgumentException(status + " 输入不得包含 Hash 或字节数");
            }
        }
    }

    private static String checkedOptionalPath(String value) {
        if (value == null) {
            throw new IllegalArgumentException("relativePath 不能为 null");
        }
        return value.isEmpty() ? value : ContractChecks.requirePortableRelativePath(value, "relativePath");
    }
}
