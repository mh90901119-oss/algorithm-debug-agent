package org.example.algorithmdebug.contracts.investigation;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 一个需要通过源码查询或动态观测消除的证据缺口。
 *
 * @param schemaVersion Schema 版本
 * @param gapId Gap ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param question 要回答的区分问题
 * @param status Gap 状态
 * @param hypothesisIds 关联假设
 * @param predicateIds 冻结 Predicate
 * @param createdAt 创建时间
 */
public record EvidenceGap(
        String schemaVersion,
        EvidenceGapId gapId,
        CaseId caseId,
        AnalysisId analysisId,
        String question,
        EvidenceGapStatus status,
        List<HypothesisId> hypothesisIds,
        List<ObservationPredicateId> predicateIds,
        Instant createdAt) {

    /** 校验 Gap 归属、说明和关联对象。 */
    public EvidenceGap {
        InvestigationContractChecks.version(schemaVersion, SchemaVersions.EVIDENCE_GAP, "EvidenceGap");
        gapId = InvestigationContractChecks.notNull(gapId, "gapId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        question = InvestigationContractChecks.text(
                question, "question", InvestigationLimits.MAX_TEXT_LENGTH);
        status = InvestigationContractChecks.notNull(status, "status");
        hypothesisIds = InvestigationContractChecks.unique(
                hypothesisIds, "hypothesisIds", InvestigationLimits.MAX_HYPOTHESES);
        if (hypothesisIds.isEmpty()) {
            throw new IllegalArgumentException("hypothesisIds must not be empty");
        }
        predicateIds = InvestigationContractChecks.unique(
                predicateIds, "predicateIds", InvestigationLimits.MAX_PREDICATES);
        createdAt = InvestigationContractChecks.notNull(createdAt, "createdAt");
    }
}
