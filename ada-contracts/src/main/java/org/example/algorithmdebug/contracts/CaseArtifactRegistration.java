package org.example.algorithmdebug.contracts;

import java.time.Instant;

/** Case 内一个可按 ID 读取的不可变 Artifact 注册记录。 */
public record CaseArtifactRegistration(
        String schemaVersion,
        CaseId caseId,
        ArtifactReference artifact,
        Instant registeredAt) {

    /** 校验版本、Case 身份、引用和时间。 */
    public CaseArtifactRegistration {
        if (!SchemaVersions.CASE_ARTIFACT_REGISTRATION.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 CaseArtifactRegistration schemaVersion");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        artifact = ContractChecks.requireNonNull(artifact, "artifact");
        registeredAt = ContractChecks.requireNonNull(registeredAt, "registeredAt");
    }
}
