package org.example.algorithmdebug.contracts;

import java.util.List;

/** Case Workspace 文件、Artifact 和交互日志的只读审计结果。 */
public record CaseWorkspaceAudit(
        String schemaVersion,
        CaseId caseId,
        boolean passed,
        int checkedArtifactCount,
        List<String> expectedArtifacts,
        List<String> actualArtifacts,
        List<CaseAuditIssue> issues) {
    public CaseWorkspaceAudit {
        if (!"1.0".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported schemaVersion");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        if (checkedArtifactCount < 0) throw new IllegalArgumentException("checkedArtifactCount must not be negative");
        expectedArtifacts = List.copyOf(ContractChecks.requireNonNull(expectedArtifacts, "expectedArtifacts"));
        actualArtifacts = List.copyOf(ContractChecks.requireNonNull(actualArtifacts, "actualArtifacts"));
        issues = List.copyOf(ContractChecks.requireNonNull(issues, "issues"));
        if (passed != issues.isEmpty()) throw new IllegalArgumentException("passed must match issues");
    }
}
