package org.example.algorithmdebug.validator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.ValidationFinding;

/** 对本地只读产物执行普通文件、字节数和 SHA-256 校验。 */
public final class ArtifactIntegrityVerifier {

    /**
     * @param reference 归档时生成的不可变引用
     * @param path 当前 Case 内的实际路径
     * @return 空列表表示完整匹配，否则返回一个稳定 INVALID Finding
     */
    public List<ValidationFinding> verify(ArtifactReference reference, Path path) {
        if (reference == null || path == null) {
            throw new IllegalArgumentException("Artifact 校验参数不能为空");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return finding("ARTIFACT_MISSING", "归档产物不存在", reference);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return finding("ARTIFACT_NOT_REGULAR", "归档产物不是普通文件", reference);
        }
        try {
            if (Files.size(path) != reference.sizeBytes()) {
                return finding("ARTIFACT_SIZE_MISMATCH", "归档产物字节数与引用不一致", reference);
            }
            if (!sha256(path).equals(reference.sha256())) {
                return finding("ARTIFACT_HASH_MISMATCH", "归档产物 SHA-256 与引用不一致", reference);
            }
            return List.of();
        } catch (IOException | SecurityException failure) {
            return finding("ARTIFACT_READ_FAILED", "无法读取归档产物", reference);
        }
    }

    /** 流式计算普通文件 SHA-256，不把整个产物加载到内存。 */
    public String sha256(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("path 不能为空");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", failure);
        }
        byte[] buffer = new byte[8 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<ValidationFinding> finding(
            String code,
            String detail,
            ArtifactReference reference) {
        return List.of(new ValidationFinding(
                code, EvidenceValidationStatus.INVALID, detail,
                List.of(reference), Optional.empty()));
    }
}
