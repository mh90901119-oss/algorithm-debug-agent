package org.example.algorithmdebug.contracts.investigation;

import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 动态采集 Plan 对开放 Gap、假设和冻结 Predicate 的不可变绑定。
 *
 * @param schemaVersion Schema 版本
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param gapId 开放 Gap ID
 * @param hypothesisIds 目标假设 ID
 * @param predicateIds 一至八个冻结 Predicate ID
 * @param basedOnEvidenceIds 规划依据 Evidence ID
 */
public record InvestigationBinding(
        String schemaVersion,
        CaseId caseId,
        AnalysisId analysisId,
        EvidenceGapId gapId,
        List<HypothesisId> hypothesisIds,
        List<ObservationPredicateId> predicateIds,
        List<EvidenceId> basedOnEvidenceIds) {

    /** 校验身份和一至八个 Predicate 的固定预算。 */
    public InvestigationBinding {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.INVESTIGATION_BINDING, "InvestigationBinding");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        gapId = InvestigationContractChecks.notNull(gapId, "gapId");
        hypothesisIds = InvestigationContractChecks.unique(
                hypothesisIds, "hypothesisIds", InvestigationLimits.MAX_HYPOTHESES);
        predicateIds = InvestigationContractChecks.unique(
                predicateIds, "predicateIds", InvestigationLimits.MAX_PREDICATES_PER_BINDING);
        basedOnEvidenceIds = InvestigationContractChecks.unique(
                basedOnEvidenceIds, "basedOnEvidenceIds", InvestigationLimits.MAX_REFERENCES);
        if (hypothesisIds.isEmpty()) {
            throw new IllegalArgumentException("hypothesisIds must not be empty");
        }
        if (predicateIds.size() < InvestigationLimits.MIN_PREDICATES_PER_BINDING) {
            throw new IllegalArgumentException("predicateIds must not be empty");
        }
    }

    /** 从同 Analysis 的开放 Gap、假设和 Predicate 构造已验证绑定。 */
    public static InvestigationBinding bind(
            EvidenceGap gap,
            List<HypothesisRecord> hypotheses,
            List<ObservationPredicate> predicates,
            List<EvidenceId> basedOnEvidenceIds) {
        EvidenceGap checkedGap = InvestigationContractChecks.notNull(gap, "gap");
        List<HypothesisRecord> checkedHypotheses = InvestigationContractChecks.unique(
                hypotheses, "hypotheses", InvestigationLimits.MAX_HYPOTHESES);
        List<ObservationPredicate> checkedPredicates = InvestigationContractChecks.unique(
                predicates, "predicates", InvestigationLimits.MAX_PREDICATES_PER_BINDING);
        if (checkedGap.status() != EvidenceGapStatus.OPEN) {
            throw new IllegalArgumentException("Plan binding requires an OPEN EvidenceGap");
        }
        if (checkedHypotheses.isEmpty() || checkedPredicates.isEmpty()) {
            throw new IllegalArgumentException("Plan binding requires hypotheses and predicates");
        }
        if (checkedPredicates.stream().noneMatch(
                value -> value.role() == PredicateRole.CRITICAL)) {
            throw new IllegalArgumentException(
                    "Plan binding requires at least one CRITICAL Predicate");
        }
        checkedHypotheses.forEach(value -> requireIdentity(
                checkedGap.caseId(), checkedGap.analysisId(), value.caseId(), value.analysisId()));
        checkedPredicates.forEach(value -> {
            requireIdentity(
                    checkedGap.caseId(), checkedGap.analysisId(), value.caseId(), value.analysisId());
            if (!value.gapId().equals(checkedGap.gapId())) {
                throw new IllegalArgumentException("Predicate must belong to the bound Gap");
            }
        });
        List<HypothesisId> ids = checkedHypotheses.stream()
                .map(HypothesisRecord::hypothesisId).toList();
        if (checkedPredicates.stream().anyMatch(value -> !ids.contains(value.hypothesisId()))) {
            throw new IllegalArgumentException("Predicate Hypothesis must be selected by the binding");
        }
        return new InvestigationBinding(
                SchemaVersions.INVESTIGATION_BINDING, checkedGap.caseId(), checkedGap.analysisId(),
                checkedGap.gapId(), ids,
                checkedPredicates.stream().map(ObservationPredicate::predicateId).toList(),
                basedOnEvidenceIds);
    }

    private static void requireIdentity(
            CaseId caseId, AnalysisId analysisId, CaseId actualCaseId, AnalysisId actualAnalysisId) {
        if (!caseId.equals(actualCaseId) || !analysisId.equals(actualAnalysisId)) {
            throw new IllegalArgumentException("Investigation binding identity mismatch");
        }
    }
}
