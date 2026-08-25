package org.example.algorithmdebug.contracts;

/** Case Workspace 确定性审计发现的问题。 */
public record CaseAuditIssue(
        String code,
        String scopeType,
        String scopeId,
        String expectedArtifactType,
        String relativePath,
        String message) {
    public CaseAuditIssue {
        code = ContractChecks.requireBoundedText(code, "code", 128, false);
        scopeType = ContractChecks.requireBoundedText(scopeType, "scopeType", 64, false);
        scopeId = ContractChecks.requireBoundedText(scopeId, "scopeId", 256, false);
        expectedArtifactType = expectedArtifactType == null ? "" : expectedArtifactType;
        relativePath = relativePath == null ? "" : relativePath;
        message = ContractChecks.requireBoundedText(message, "message", 1_024, false);
    }
}
