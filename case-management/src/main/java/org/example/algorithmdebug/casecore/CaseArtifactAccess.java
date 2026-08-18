package org.example.algorithmdebug.casecore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;

/** 将不受信任的 Case 相对 Artifact 名称转换为经过边界校验的本地只读文件。 */
public final class CaseArtifactAccess {

    private final Path casesRoot;

    /** @param casesRoot 已存在且不为符号链接的项目 Case 根目录 */
    public CaseArtifactAccess(Path casesRoot) {
        if (casesRoot == null) throw new IllegalArgumentException("casesRoot 不能为空");
        this.casesRoot = casesRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.casesRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(this.casesRoot)) {
            throw new WorkspaceException("CASE_ARCHIVE_PATH_INVALID", "Case 根目录非法");
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
            throw new IllegalArgumentException("Artifact 解析参数非法");
        }
        validateRelativePath(relativePath);
        Path caseRoot = CaseArchiveLayout.of(casesRoot, caseId).caseRoot();
        Path candidate = caseRoot.resolve(relativePath.replace('/', java.io.File.separatorChar))
                .toAbsolutePath().normalize();
        if (!candidate.startsWith(caseRoot) || candidate.equals(caseRoot)) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact 路径越界");
        }
        rejectSymbolicComponents(caseRoot, candidate);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("CASE_ARTIFACT_NOT_FOUND", "Artifact 不存在或不是普通文件");
        }
        try {
            if (Files.size(candidate) > maxBytes) {
                throw new WorkspaceException("CASE_ARTIFACT_TOO_LARGE", "Artifact 超过读取预算");
            }
            return candidate;
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("CASE_ARTIFACT_READ_FAILED", "无法读取 Artifact 元数据", failure);
        }
    }

    /** 将已验证文件描述为不泄漏绝对路径的 ArtifactReference。 */
    public ArtifactReference describe(
            CaseId caseId,
            String artifactId,
            String artifactType,
            String mediaType,
            Path path) {
        if (caseId == null || path == null) throw new IllegalArgumentException("Artifact 描述参数非法");
        Path caseRoot = CaseArchiveLayout.of(casesRoot, caseId).caseRoot();
        Path checked = path.toAbsolutePath().normalize();
        if (!checked.startsWith(caseRoot) || checked.equals(caseRoot)
                || !Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact 不属于指定 Case");
        }
        rejectSymbolicComponents(caseRoot, checked);
        try {
            return new ArtifactReference(
                    artifactId, artifactType,
                    caseRoot.relativize(checked).toString().replace('\\', '/'),
                    mediaType, sha256(checked), Files.size(checked));
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException("CASE_ARTIFACT_READ_FAILED", "无法描述 Artifact", failure);
        }
    }

    private static void validateRelativePath(String value) {
        if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact 必须使用可移植相对路径");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact 包含非法路径段");
            }
        }
    }

    private static void rejectSymbolicComponents(Path root, Path candidate) {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Case 路径包含符号链接");
        }
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new WorkspaceException("CASE_ARTIFACT_PATH_INVALID", "Artifact 路径包含符号链接");
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
        byte[] buffer = new byte[8 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
