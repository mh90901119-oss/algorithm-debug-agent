package org.example.algorithmdebug.contracts;

/**
 * 对某次运行目录内不可变产物的可移植引用。
 *
 * @param artifactId 运行内唯一的产物 ID
 * @param artifactType 可扩展的产物类型代码
 * @param relativePath 使用 `/` 分隔、相对于运行根目录的路径
 * @param mediaType 产物媒体类型
 * @param sha256 产物内容 SHA-256
 * @param sizeBytes 产物字节数
 */
public record ArtifactReference(
        String artifactId,
        String artifactType,
        String relativePath,
        String mediaType,
        String sha256,
        long sizeBytes) {

    /** 校验产物引用可迁移、可校验且不逃逸运行目录。 */
    public ArtifactReference {
        artifactId = ContractChecks.requireOpaqueId(artifactId, "artifactId");
        artifactType = ContractChecks.requireNonBlank(artifactType, "artifactType");
        relativePath = ContractChecks.requirePortableRelativePath(relativePath, "relativePath");
        mediaType = ContractChecks.requireNonBlank(mediaType, "mediaType");
        sha256 = ContractChecks.requireSha256(sha256, "sha256");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数");
        }
    }
}

