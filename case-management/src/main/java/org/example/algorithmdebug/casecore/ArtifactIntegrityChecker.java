package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ArtifactReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 对归档文件执行统一、确定性的 {@link ArtifactReference} 完整性校验。
 *
 * <p>该组件只验证文件类型、字节数和 SHA-256，不负责解析文件语义，也不调用 LLM。</p>
 */
public final class ArtifactIntegrityChecker {

    /** 完整性校验结果。 */
    public enum Status {
        VALID,
        MISSING,
        NOT_REGULAR,
        SIZE_MISMATCH,
        HASH_MISMATCH,
        READ_FAILED
    }

    /**
     * @param status 校验状态
     */
    public record Result(Status status) {
        public Result {
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * 校验指定路径是否仍与归档引用一致。
     *
     * @param reference 归档时记录的文件引用
     * @param path 当前文件路径
     * @return 确定性的校验结果
     */
    public Result verify(ArtifactReference reference, Path path) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(path, "path");

        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new Result(Status.MISSING);
            }
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return new Result(Status.NOT_REGULAR);
            }
            if (Files.size(path) != reference.sizeBytes()) {
                return new Result(Status.SIZE_MISMATCH);
            }
            if (!sha256(path).equalsIgnoreCase(reference.sha256())) {
                return new Result(Status.HASH_MISMATCH);
            }
            return new Result(Status.VALID);
        } catch (IOException | SecurityException exception) {
            return new Result(Status.READ_FAILED);
        }
    }

    /**
     * 计算文件内容的 SHA-256。
     *
     * @param path 普通文件路径
     * @return 小写十六进制 SHA-256
     * @throws IOException 文件无法读取时抛出
     */
    public String sha256(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
