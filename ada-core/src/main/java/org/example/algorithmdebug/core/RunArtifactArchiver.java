package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.ArtifactReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 在单个 Run 根目录内复制或引用有界不可变原始产物，并生成可校验引用。 */
public final class RunArtifactArchiver {

    /** 引用已经由 Harness 写入 Run 目录的普通文件。 */
    public ArtifactReference reference(
            Path runRoot,
            Path artifact,
            String artifactId,
            String artifactType,
            String mediaType,
            long maximumBytes) {
        Path root = normalizedRoot(runRoot);
        Path file = artifact == null ? null : artifact.toAbsolutePath().normalize();
        if (file == null || !file.startsWith(root) || file.equals(root)) {
            throw new CaseRunException("RUN_ARTIFACT_PATH_INVALID", "Artifact 不在 Run 目录内");
        }
        return describe(root, file, artifactId, artifactType, mediaType, maximumBytes);
    }

    /** 将外部普通文件以 create-new 语义原子复制到 Run 目录。 */
    public ArtifactReference copy(
            Path runRoot,
            Path source,
            Path relativeDestination,
            String artifactId,
            String artifactType,
            String mediaType,
            long maximumBytes) {
        Path root = normalizedRoot(runRoot);
        if (source == null || relativeDestination == null || relativeDestination.isAbsolute()
                || relativeDestination.normalize().startsWith("..")) {
            throw new CaseRunException("RUN_ARTIFACT_PATH_INVALID", "Artifact 复制路径非法");
        }
        Path from = source.toAbsolutePath().normalize();
        Path destination = root.resolve(relativeDestination).normalize();
        if (!destination.startsWith(root) || destination.equals(root)) {
            throw new CaseRunException("RUN_ARTIFACT_PATH_INVALID", "Artifact 复制路径越界");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new CaseRunException(
                    "RUN_ARTIFACT_ALREADY_EXISTS", "拒绝覆盖已有 Run Artifact");
        }
        requireReadable(from, maximumBytes);
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".artifact-", ".tmp");
            try {
                Files.copy(from, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException failure) {
                    throw new CaseRunException(
                            "RUN_ARTIFACT_ATOMIC_MOVE_UNSUPPORTED",
                            "文件系统不支持安全提交 Artifact", failure);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (CaseRunException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new CaseRunException("RUN_ARTIFACT_WRITE_FAILED", "无法归档 Run Artifact", failure);
        }
        return describe(root, destination, artifactId, artifactType, mediaType, maximumBytes);
    }

    private static ArtifactReference describe(
            Path root,
            Path file,
            String artifactId,
            String artifactType,
            String mediaType,
            long maximumBytes) {
        long size = requireReadable(file, maximumBytes);
        try {
            return new ArtifactReference(
                    artifactId, artifactType, portable(root.relativize(file)), mediaType,
                    sha256(file), size);
        } catch (IOException | SecurityException failure) {
            throw new CaseRunException("RUN_ARTIFACT_READ_FAILED", "无法校验 Run Artifact", failure);
        }
    }

    private static long requireReadable(Path file, long maximumBytes) {
        if (maximumBytes <= 0 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new CaseRunException("RUN_ARTIFACT_INVALID", "Artifact 不是可读普通文件");
        }
        try {
            long size = Files.size(file);
            if (size > maximumBytes) {
                throw new CaseRunException("RUN_ARTIFACT_TOO_LARGE", "Artifact 超过大小预算");
            }
            return size;
        } catch (IOException | SecurityException failure) {
            throw new CaseRunException("RUN_ARTIFACT_READ_FAILED", "无法读取 Artifact 状态", failure);
        }
    }

    private static Path normalizedRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("runRoot 不能为空");
        }
        return root.toAbsolutePath().normalize();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", failure);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String portable(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
