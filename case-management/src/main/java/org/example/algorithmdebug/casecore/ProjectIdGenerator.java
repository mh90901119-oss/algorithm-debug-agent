package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 根据算法模块规范化路径生成稳定、路径安全的默认 ProjectId。 */
public final class ProjectIdGenerator {

    private static final int HASH_LENGTH = 12;
    private static final int MAX_PROJECT_ID_LENGTH = 128;

    /**
     * 生成“模块名-SHA256前12位”形式的确定性 ID。
     *
     * @param canonicalModuleRoot 已规范化的算法模块绝对路径
     * @return 小写且可作为单一路径段的 ProjectId
     */
    public ProjectId generate(Path canonicalModuleRoot) {
        if (canonicalModuleRoot == null) {
            throw new IllegalArgumentException("canonicalModuleRoot must not be null");
        }
        Path normalized = canonicalModuleRoot.toAbsolutePath().normalize();
        String hash = sha256(normalized.toString()).substring(0, HASH_LENGTH);
        String slug = slug(normalized.getFileName());
        int maxSlugLength = MAX_PROJECT_ID_LENGTH - HASH_LENGTH - 1;
        if (slug.length() > maxSlugLength) {
            slug = slug.substring(0, maxSlugLength);
        }
        return new ProjectId(slug + "-" + hash);
    }

    private static String slug(Path fileName) {
        String raw = fileName == null ? "project" : fileName.toString();
        String value = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return value.isEmpty() ? "project" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not support SHA-256", impossible);
        }
    }
}
