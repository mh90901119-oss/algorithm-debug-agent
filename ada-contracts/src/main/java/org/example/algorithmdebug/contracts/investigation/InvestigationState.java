package org.example.algorithmdebug.contracts.investigation;

import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 从已校验 Investigation Event 重放得到的不可变状态。
 *
 * @param schemaVersion Schema 版本
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param problemFrame 唯一 Problem Frame
 * @param hypotheses 当前假设投影
 * @param gaps 当前 Gap 投影
 * @param predicates 冻结 Predicate
 * @param evaluations 系统 Evaluation
 * @param lastSequence 最后事件序号
 * @param limitations 重放限制
 */
public record InvestigationState(
        String schemaVersion,
        CaseId caseId,
        AnalysisId analysisId,
        ProblemFrame problemFrame,
        List<HypothesisRecord> hypotheses,
        List<EvidenceGap> gaps,
        List<ObservationPredicate> predicates,
        List<ObservationEvaluation> evaluations,
        long lastSequence,
        List<String> limitations) {

    /** 校验所有投影对象属于同一 Analysis 且 ID 唯一。 */
    public InvestigationState {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.INVESTIGATION_STATE, "InvestigationState");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        problemFrame = InvestigationContractChecks.notNull(problemFrame, "problemFrame");
        requireIdentity(caseId, analysisId, problemFrame.caseId(), problemFrame.analysisId());
        hypotheses = InvestigationContractChecks.unique(
                hypotheses, "hypotheses", InvestigationLimits.MAX_HYPOTHESES);
        gaps = InvestigationContractChecks.unique(gaps, "gaps", InvestigationLimits.MAX_GAPS);
        predicates = InvestigationContractChecks.unique(
                predicates, "predicates", InvestigationLimits.MAX_PREDICATES);
        evaluations = InvestigationContractChecks.unique(
                evaluations, "evaluations", InvestigationLimits.MAX_EVALUATIONS);
        limitations = InvestigationContractChecks.uniqueTexts(
                limitations, "limitations", InvestigationLimits.MAX_LIMITATIONS);
        if (lastSequence < 1) {
            throw new IllegalArgumentException("lastSequence must be positive");
        }
        requireUniqueIds(hypotheses, HypothesisRecord::hypothesisId, "hypothesisId");
        requireUniqueIds(gaps, EvidenceGap::gapId, "gapId");
        requireUniqueIds(predicates, ObservationPredicate::predicateId, "predicateId");
        requireUniqueIds(evaluations, ObservationEvaluation::evaluationId, "evaluationId");
        for (HypothesisRecord value : hypotheses) {
            requireIdentity(caseId, analysisId, value.caseId(), value.analysisId());
        }
        for (EvidenceGap value : gaps) {
            requireIdentity(caseId, analysisId, value.caseId(), value.analysisId());
        }
        for (ObservationPredicate value : predicates) {
            requireIdentity(caseId, analysisId, value.caseId(), value.analysisId());
        }
        for (ObservationEvaluation value : evaluations) {
            requireIdentity(caseId, analysisId, value.caseId(), value.analysisId());
        }
    }

    private static <T, I> void requireUniqueIds(
            List<T> values, Function<T, I> identity, String field) {
        HashSet<I> ids = new HashSet<>();
        if (values.stream().map(identity).anyMatch(value -> !ids.add(value))) {
            throw new IllegalArgumentException("Duplicate " + field);
        }
    }

    private static void requireIdentity(
            CaseId expectedCase, AnalysisId expectedAnalysis,
            CaseId actualCase, AnalysisId actualAnalysis) {
        if (!expectedCase.equals(actualCase) || !expectedAnalysis.equals(actualAnalysis)) {
            throw new IllegalArgumentException("InvestigationState identity mismatch");
        }
    }
}
