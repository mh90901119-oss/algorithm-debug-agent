package org.example.algorithmdebug.contracts.investigation;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;

/**
 * 可被支持或反证、但不能由模型直接定状态的调查假设。
 *
 * @param schemaVersion Schema 版本
 * @param hypothesisId 假设 ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param statement 假设陈述
 * @param status reducer 产生的状态
 * @param sourceAnchorRefs 源码锚点引用
 * @param supportingEvaluationIds 支持 Evaluation ID
 * @param contradictingEvaluationIds 反证 Evaluation ID
 * @param gapIds 关联 Gap ID
 * @param createdAt 创建时间
 */
public record HypothesisRecord(
        String schemaVersion,
        HypothesisId hypothesisId,
        CaseId caseId,
        AnalysisId analysisId,
        String statement,
        HypothesisStatus status,
        List<SourceAnchor> sourceAnchorRefs,
        List<ObservationEvaluationId> supportingEvaluationIds,
        List<ObservationEvaluationId> contradictingEvaluationIds,
        List<EvidenceGapId> gapIds,
        Instant createdAt) {

    /** 校验状态必须由相应 Evaluation 支撑。 */
    public HypothesisRecord {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.HYPOTHESIS_RECORD, "HypothesisRecord");
        hypothesisId = InvestigationContractChecks.notNull(hypothesisId, "hypothesisId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        statement = InvestigationContractChecks.text(
                statement, "statement", InvestigationLimits.MAX_TEXT_LENGTH);
        status = InvestigationContractChecks.notNull(status, "status");
        sourceAnchorRefs = InvestigationContractChecks.unique(
                sourceAnchorRefs, "sourceAnchorRefs", InvestigationLimits.MAX_SCOPE_ANCHORS);
        supportingEvaluationIds = InvestigationContractChecks.unique(
                supportingEvaluationIds, "supportingEvaluationIds",
                InvestigationLimits.MAX_EVALUATIONS);
        contradictingEvaluationIds = InvestigationContractChecks.unique(
                contradictingEvaluationIds, "contradictingEvaluationIds",
                InvestigationLimits.MAX_EVALUATIONS);
        gapIds = InvestigationContractChecks.unique(
                gapIds, "gapIds", InvestigationLimits.MAX_GAPS);
        createdAt = InvestigationContractChecks.notNull(createdAt, "createdAt");
        if (status == HypothesisStatus.SUPPORTED && supportingEvaluationIds.isEmpty()) {
            throw new IllegalArgumentException("SUPPORTED requires supporting Evaluation");
        }
        if (status == HypothesisStatus.REFUTED && contradictingEvaluationIds.isEmpty()) {
            throw new IllegalArgumentException("REFUTED requires contradicting Evaluation");
        }
        if (supportingEvaluationIds.stream().anyMatch(contradictingEvaluationIds::contains)) {
            throw new IllegalArgumentException(
                    "An Evaluation cannot both support and contradict a Hypothesis");
        }
    }
}
