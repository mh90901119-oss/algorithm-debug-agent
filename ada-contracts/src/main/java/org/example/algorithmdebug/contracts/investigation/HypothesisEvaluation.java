package org.example.algorithmdebug.contracts.investigation;

import java.util.List;

/**
 * reducer 对假设状态转换的可审计摘要。
 *
 * @param hypothesisId 假设 ID
 * @param previousStatus 原状态
 * @param newStatus 新状态
 * @param supportingEvaluationIds 支持 Evaluation
 * @param contradictingEvaluationIds 反证 Evaluation
 * @param limitations 限制说明
 */
public record HypothesisEvaluation(
        HypothesisId hypothesisId,
        HypothesisStatus previousStatus,
        HypothesisStatus newStatus,
        List<ObservationEvaluationId> supportingEvaluationIds,
        List<ObservationEvaluationId> contradictingEvaluationIds,
        List<String> limitations) {

    /** 校验引用和 REFUTED 终态。 */
    public HypothesisEvaluation {
        hypothesisId = InvestigationContractChecks.notNull(hypothesisId, "hypothesisId");
        previousStatus = InvestigationContractChecks.notNull(previousStatus, "previousStatus");
        newStatus = InvestigationContractChecks.notNull(newStatus, "newStatus");
        supportingEvaluationIds = InvestigationContractChecks.unique(
                supportingEvaluationIds, "supportingEvaluationIds",
                InvestigationLimits.MAX_EVALUATIONS);
        contradictingEvaluationIds = InvestigationContractChecks.unique(
                contradictingEvaluationIds, "contradictingEvaluationIds",
                InvestigationLimits.MAX_EVALUATIONS);
        limitations = InvestigationContractChecks.uniqueTexts(
                limitations, "limitations", InvestigationLimits.MAX_LIMITATIONS);
        if (previousStatus == HypothesisStatus.REFUTED && newStatus != HypothesisStatus.REFUTED) {
            throw new IllegalArgumentException("REFUTED is terminal");
        }
    }
}
