package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;

/** 将不受信任的 Case 相对 Artifact 名称转换为经过边界校验的本地只读文件。 */
public final class CaseArtifactAccess {

    private final Path casesRoot;
    private final ArtifactIntegrityChecker integrityChecker = new ArtifactIntegrityChecker();

    /** @param casesRoot 已存在且不为符号链接的项目 Case 根目录 */
    public CaseArtifactAccess(Path casesRoot) {
        if (casesRoot == null) throw new IllegalArgumentException("casesRoot must not be null");
        this.casesRoot = casesRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.casesRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(this.casesRoot)) {
            throw new WorkspaceException("CASE_ARCHIVE_PATH_INVALID", "Case root directory is invalid");
        }
    }

    /**
     * 解析一个有界普通文件，并拒绝绝对路径、目录逃逸和符号链接。
     *
     * @param caseId Case 身份
     * @param relativePath 使用 `/` 分隔的 Case 相对路径
     * @param maxBytes 调用方允许读取的最大字节数
     * @return 经过校验的绝对文件路径
     */
    public Path requireRegularArtifact(CaseId caseId, String relativePath, long maxBytes) {
        if (caseId == null || relativePath == null || maxBytes < 1) {
            throw new IllegalArgumentException("Artifact resolution parameters are invalid");
        }
        validateRelativePath(relativePath);
        Path caseRoot = CaseArchiveLayout.of(casesRoot, caseId).caseRoot();
        Path candidate = caseRoot.resolve(relativePath.replace('/', java.io.File.separatorChar))
                .toAbsolutePath().normalize();
        if (!candidate.startsWith(caseRoot) || candidate.equals(caseRoot)) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact path escapes its root");
        }
        rejectSymbolicComponents(caseRoot, candidate);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("CASE_ARTIFACT_NOT_FOUND", "The Artifact does not exist or is not a regular file");
        }
        try {
            if (Files.size(candidate) > maxBytes) {
                throw new WorkspaceException("CASE_ARTIFACT_TOO_LARGE", "Artifact exceeds the read budget");
            }
            return candidate;
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("CASE_ARTIFACT_READ_FAILED", "Failed to read Artifact metadata", failure);
        }
    }

    /**
     * 解析已注册文件，并验证当前字节数和 SHA-256 仍与归档引用一致。
     *
     * @param caseId Case 身份
     * @param reference 已归档的 Artifact 引用
     * @return 通过路径边界和完整性校验的绝对路径
     */
    public Path requireVerifiedArtifact(CaseId caseId, ArtifactReference reference) {
        if (caseId == null || reference == null) {
            throw new IllegalArgumentException("Artifact integrity verification parameters are invalid");
        }
        Path path = requireRegularArtifact(caseId, reference.relativePath(), Long.MAX_VALUE);
        ArtifactIntegrityChecker.Status status = integrityChecker.verify(reference, path).status();
        return switch (status) {
            case VALID -> path;
            case MISSING, NOT_REGULAR -> throw new WorkspaceException(
                    "CASE_ARTIFACT_NOT_FOUND", "The Artifact does not exist or is not a regular file");
            case SIZE_MISMATCH, HASH_MISMATCH -> throw new WorkspaceException(
                    "CASE_ARTIFACT_INTEGRITY_MISMATCH", "Artifact does not match its registered reference");
            case READ_FAILED -> throw new WorkspaceException(
                    "CASE_ARTIFACT_READ_FAILED", "Failed to read Artifact metadata or content");
        };
    }

    /** 将已验证文件描述为不泄漏绝对路径的 ArtifactReference。 */
    public ArtifactReference describe(
            CaseId caseId,
            String artifactId,
            String artifactType,
            String mediaType,
            Path path) {
        if (caseId == null || path == null) throw new IllegalArgumentException("Artifact description parameters are invalid");
        Path caseRoot = CaseArchiveLayout.of(casesRoot, caseId).caseRoot();
        Path checked = path.toAbsolutePath().normalize();
        if (!checked.startsWith(caseRoot) || checked.equals(caseRoot)
                || !Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact does not belong to the specified Case");
        }
        rejectSymbolicComponents(caseRoot, checked);
        try {
            return new ArtifactReference(
                    artifactId, artifactType,
                    caseRoot.relativize(checked).toString().replace('\\', '/'),
                    mediaType, integrityChecker.sha256(checked), Files.size(checked));
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("CASE_ARTIFACT_READ_FAILED", "Failed to describe Artifact", failure);
        }
    }

    private static void validateRelativePath(String value) {
        if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact must use a portable relative path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact contains an invalid path segment");
            }
        }
    }

    private static void rejectSymbolicComponents(Path root, Path candidate) {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Case path contains a symbolic link");
        }
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact path contains a symbolic link");
            }
        }
    }

}
