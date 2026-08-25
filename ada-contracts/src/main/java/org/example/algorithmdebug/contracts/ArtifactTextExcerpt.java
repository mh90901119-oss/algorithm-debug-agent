package org.example.algorithmdebug.contracts;

/** 按注册 Artifact ID 返回的有界 UTF-8 文本片段。 */
public record ArtifactTextExcerpt(
        ArtifactReference artifact,
        long offsetBytes,
        long nextOffsetBytes,
        boolean truncated,
        String text) {

    /** 校验引用、偏移关系和最大文本预算。 */
    public ArtifactTextExcerpt {
        artifact = ContractChecks.requireNonNull(artifact, "artifact");
        if (offsetBytes < 0 || nextOffsetBytes < offsetBytes
                || nextOffsetBytes > artifact.sizeBytes()) {
            throw new IllegalArgumentException("Artifact excerpt 偏移非法");
        }
        if (truncated != (nextOffsetBytes < artifact.sizeBytes())) {
            throw new IllegalArgumentException("Artifact excerpt truncated 与偏移不一致");
        }
        if (text == null || text.length() > 65_536) {
            throw new IllegalArgumentException("text 不能为 null 且长度不能超过 65536");
        }
    }
}
