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
            throw new CaseRunException("RUN_ARTIFACT_PATH_INVALID", "Artifact is outside the Run directory");
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
            throw new CaseRunException("RUN_ARTIFACT_PATH_INVALID", "Artifact copy path is invalid");
        }
        Path from = source.toAbsolutePath().normalize();
        Path destination = root.resolve(relativeDestination).normalize();
        if (!destination.startsWith(root) || destination.equals(root)) {
            throw new CaseRunException("RUN_ARTIFACT_PATH_INVALID", "Artifact copy path escapes its root");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new CaseRunException(
                    "RUN_ARTIFACT_ALREADY_EXISTS", "Refusing to overwrite an existing Run Artifact");
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
                            "File system does not support safe Artifact commit", failure);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (CaseRunException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new CaseRunException("RUN_ARTIFACT_WRITE_FAILED", "Failed to archive Run Artifact", failure);
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
            throw new CaseRunException("RUN_ARTIFACT_READ_FAILED", "Failed to verify Run Artifact", failure);
        }
    }

    private static long requireReadable(Path file, long maximumBytes) {
        if (maximumBytes <= 0 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new CaseRunException("RUN_ARTIFACT_INVALID", "Artifact is not a readable regular file");
        }
        try {
            long size = Files.size(file);
            if (size > maximumBytes) {
                throw new CaseRunException("RUN_ARTIFACT_TOO_LARGE", "Artifact exceeds the size budget");
            }
            return size;
        } catch (IOException | SecurityException failure) {
            throw new CaseRunException("RUN_ARTIFACT_READ_FAILED", "Failed to read Artifact status", failure);
        }
    }

    private static Path normalizedRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("runRoot must not be null");
        }
        return root.toAbsolutePath().normalize();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("current JVM does not support SHA-256", failure);
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
