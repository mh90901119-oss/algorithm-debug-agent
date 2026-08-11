package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ArtifactReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 使用临时文件和原子提交向 Run 目录写入不可变产物。 */
public final class ImmutableArtifactStore {

    /**
     * 复制一份产物并返回相对于 Run 根目录的可移植引用；目标已存在时拒绝覆盖。
     */
    public ArtifactReference copy(
            Path source,
            Path runRoot,
            Path relativeTarget,
            String artifactId,
            String artifactType,
            String mediaType) throws IOException {
        if (source == null || runRoot == null || relativeTarget == null) {
            throw new IllegalArgumentException("产物路径不能为空");
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("源产物不存在: " + source);
        }
        if (relativeTarget.isAbsolute() || relativeTarget.normalize().startsWith("..")) {
            throw new IllegalArgumentException("relativeTarget 必须位于 Run 目录内");
        }
        Path root = runRoot.toAbsolutePath().normalize();
        Path target = root.resolve(relativeTarget).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("产物目标逃逸 Run 目录");
        }
        if (Files.exists(target)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".artifact-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        String portable = root.relativize(target).toString().replace('\\', '/');
        return new ArtifactReference(
                artifactId,
                artifactType,
                portable,
                mediaType,
                sha256(target),
                Files.size(target));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }
}
