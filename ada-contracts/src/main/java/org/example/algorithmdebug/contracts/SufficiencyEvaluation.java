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
            throw new IllegalArgumentException("不支持的 SufficiencyEvaluation schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        status = ContractChecks.requireNonNull(status, "status");
        requiredDimensions = EvidenceBuildRequest.immutableDimensions(
                requiredDimensions, "requiredDimensions");
        if (requiredDimensions.isEmpty()) {
            throw new IllegalArgumentException("requiredDimensions 不能为空");
        }
        coveredDimensions = EvidenceBuildRequest.immutableDimensions(
                coveredDimensions, "coveredDimensions");
        missingDimensions = EvidenceBuildRequest.immutableDimensions(
                missingDimensions, "missingDimensions");
        contradictions = ContractChecks.immutableBoundedStrings(
                contradictions, "contradictions", 2_048);
        if (contradictions.size() > 64) throw new IllegalArgumentException("contradictions 超限");
        evaluatedAt = ContractChecks.requireNonNull(evaluatedAt, "evaluatedAt");
        HashSet<EvidenceDimension> expectedMissing = new HashSet<>(requiredDimensions);
        expectedMissing.removeAll(coveredDimensions);
        if (!expectedMissing.equals(missingDimensions)) {
            throw new IllegalArgumentException("missingDimensions 与要求/覆盖集合不一致");
        }
        if (status == SufficiencyStatus.SUFFICIENT
                && (!missingDimensions.isEmpty() || !contradictions.isEmpty())) {
            throw new IllegalArgumentException("SUFFICIENT 不能有缺口或矛盾");
        }
        if (status == SufficiencyStatus.INSUFFICIENT && missingDimensions.isEmpty()) {
            throw new IllegalArgumentException("INSUFFICIENT 必须包含缺失维度");
        }
        if (status == SufficiencyStatus.CONTRADICTED && contradictions.isEmpty()) {
            throw new IllegalArgumentException("CONTRADICTED 必须包含矛盾");
        }
    }
}
