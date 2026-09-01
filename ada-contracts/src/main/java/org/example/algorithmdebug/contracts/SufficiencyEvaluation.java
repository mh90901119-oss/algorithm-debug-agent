package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Evidence Bundle 对声明维度的确定性覆盖评估。 */
public record SufficiencyEvaluation(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        SufficiencyStatus status,
        Set<EvidenceDimension> requiredDimensions,
        Set<EvidenceDimension> coveredDimensions,
        Set<EvidenceDimension> missingDimensions,
        List<String> contradictions,
        Instant evaluatedAt) {

    /** 校验集合关系与充分、不足、矛盾状态的一致性。 */
    public SufficiencyEvaluation {
        if (!SchemaVersions.SUFFICIENCY_EVALUATION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported SufficiencyEvaluation schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        status = ContractChecks.requireNonNull(status, "status");
        requiredDimensions = EvidenceBuildRequest.immutableDimensions(
                requiredDimensions, "requiredDimensions");
        if (requiredDimensions.isEmpty()) {
            throw new IllegalArgumentException("requiredDimensions must not be null");
        }
        coveredDimensions = EvidenceBuildRequest.immutableDimensions(
                coveredDimensions, "coveredDimensions");
        missingDimensions = EvidenceBuildRequest.immutableDimensions(
                missingDimensions, "missingDimensions");
        contradictions = ContractChecks.immutableBoundedStrings(
                contradictions, "contradictions", 2_048);
        if (contradictions.size() > 64) throw new IllegalArgumentException("contradictions exceeds the limit");
        evaluatedAt = ContractChecks.requireNonNull(evaluatedAt, "evaluatedAt");
        HashSet<EvidenceDimension> expectedMissing = new HashSet<>(requiredDimensions);
        expectedMissing.removeAll(coveredDimensions);
        if (!expectedMissing.equals(missingDimensions)) {
            throw new IllegalArgumentException("missingDimensions does not match required/covered dimensions");
        }
        if (status == SufficiencyStatus.SUFFICIENT
                && (!missingDimensions.isEmpty() || !contradictions.isEmpty())) {
            throw new IllegalArgumentException("SUFFICIENT must not contain gaps or contradictions");
        }
        if (status == SufficiencyStatus.INSUFFICIENT && missingDimensions.isEmpty()) {
            throw new IllegalArgumentException("INSUFFICIENT must contain missing dimensions");
        }
        if (status == SufficiencyStatus.CONTRADICTED && contradictions.isEmpty()) {
            throw new IllegalArgumentException("CONTRADICTED must contain contradictions");
        }
    }
}
