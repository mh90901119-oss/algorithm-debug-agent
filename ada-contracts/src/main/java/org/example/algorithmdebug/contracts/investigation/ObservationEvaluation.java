package org.example.algorithmdebug.contracts.investigation;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * Predicate 对一组 Evidence 输入的确定性三值评估。
 *
 * @param schemaVersion Schema 版本
 * @param evaluationId Evaluation ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param predicateId Predicate ID
 * @param truth 三值结果
 * @param evidenceIds 输入 Evidence
 * @param sourceCoverage 来源覆盖
 * @param evidenceDisposition Evidence 使用等级
 * @param effectApplied 是否应用假设效果
 * @param appliedEffect 实际效果
 * @param limitations 限制说明
 * @param inputSha256 规范化输入哈希
 * @param evaluatedAt 评估时间
 * @param evaluatorVersion evaluator 版本
 */
public record ObservationEvaluation(
        String schemaVersion,
        ObservationEvaluationId evaluationId,
        CaseId caseId,
        AnalysisId analysisId,
        ObservationPredicateId predicateId,
        ObservationTruth truth,
        List<EvidenceId> evidenceIds,
        EvidenceSourceCoverage sourceCoverage,
        EvidenceDisposition evidenceDisposition,
        boolean effectApplied,
        HypothesisEffect appliedEffect,
        List<String> limitations,
        String inputSha256,
        Instant evaluatedAt,
        String evaluatorVersion) {

    /** 校验 Evidence 资格、truth 和 effect 的正交关系。 */
    public ObservationEvaluation {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.OBSERVATION_EVALUATION, "ObservationEvaluation");
        evaluationId = InvestigationContractChecks.notNull(evaluationId, "evaluationId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        predicateId = InvestigationContractChecks.notNull(predicateId, "predicateId");
        truth = InvestigationContractChecks.notNull(truth, "truth");
        evidenceIds = InvestigationContractChecks.unique(
                evidenceIds, "evidenceIds", InvestigationLimits.MAX_REFERENCES);
        sourceCoverage = InvestigationContractChecks.notNull(sourceCoverage, "sourceCoverage");
        evidenceDisposition = InvestigationContractChecks.notNull(
                evidenceDisposition, "evidenceDisposition");
        appliedEffect = InvestigationContractChecks.notNull(appliedEffect, "appliedEffect");
        limitations = InvestigationContractChecks.uniqueTexts(
                limitations, "limitations", InvestigationLimits.MAX_LIMITATIONS);
        inputSha256 = InvestigationContractChecks.sha256(inputSha256, "inputSha256");
        evaluatedAt = InvestigationContractChecks.notNull(evaluatedAt, "evaluatedAt");
        evaluatorVersion = InvestigationContractChecks.text(
                evaluatorVersion, "evaluatorVersion", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidenceIds must not be empty");
        }
        if (truth == ObservationTruth.UNKNOWN && effectApplied) {
            throw new IllegalArgumentException("UNKNOWN cannot apply a Hypothesis effect");
        }
        if (evidenceDisposition != EvidenceDisposition.CONFIRMATION_ELIGIBLE && effectApplied) {
            throw new IllegalArgumentException(
                    "Only confirmation-eligible Evidence may apply a Hypothesis effect");
        }
        if (effectApplied == (appliedEffect == HypothesisEffect.NO_CHANGE)) {
            throw new IllegalArgumentException(
                    "effectApplied must match whether appliedEffect changes the Hypothesis");
        }
        if (evidenceDisposition == EvidenceDisposition.INVALID && truth != ObservationTruth.UNKNOWN) {
            throw new IllegalArgumentException("INVALID Evidence must produce UNKNOWN");
        }
    }
}
