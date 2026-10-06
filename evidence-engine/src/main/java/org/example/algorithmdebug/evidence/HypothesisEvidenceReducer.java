package org.example.algorithmdebug.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisEvaluation;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluationId;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;

/**
 * 使用冻结 Predicate、Gap 和 Evaluation 聚合假设状态，不解释目标算法业务语义。
 */
public final class HypothesisEvidenceReducer {
    private static final String UNKNOWN_CRITICAL_PREFIX = "UNKNOWN_CRITICAL_PREDICATE:";
    private static final String OPEN_GAP_PREFIX = "OPEN_EVIDENCE_GAP:";

    /**
     * 确定性聚合一个 Hypothesis。
     *
     * @param hypothesis 当前假设投影
     * @param predicates 同一 Analysis 的冻结 Predicate
     * @param gaps 同一 Analysis 的 Evidence Gap
     * @param evaluations 同一 Analysis 的 Observation Evaluation
     * @return 状态转换摘要；REFUTED 永远不恢复
     */
    public HypothesisEvaluation reduce(
            HypothesisRecord hypothesis,
            List<ObservationPredicate> predicates,
            List<EvidenceGap> gaps,
            List<ObservationEvaluation> evaluations) {
        Inputs input = validate(hypothesis, predicates, gaps, evaluations);
        if (hypothesis.status() == HypothesisStatus.REFUTED) {
            return new HypothesisEvaluation(
                    hypothesis.hypothesisId(), HypothesisStatus.REFUTED,
                    HypothesisStatus.REFUTED,
                    sortedIds(hypothesis.supportingEvaluationIds()),
                    sortedIds(hypothesis.contradictingEvaluationIds()), List.of());
        }

        LinkedHashSet<ObservationEvaluationId> supporting =
                new LinkedHashSet<>(hypothesis.supportingEvaluationIds());
        LinkedHashSet<ObservationEvaluationId> contradicting =
                new LinkedHashSet<>(hypothesis.contradictingEvaluationIds());
        boolean criticalRefuted = false;
        boolean anyTargetEvaluation = false;
        ArrayList<String> limitations = new ArrayList<>();

        for (ObservationPredicate predicate : input.targetPredicates()) {
            List<ObservationEvaluation> predicateEvaluations =
                    input.evaluationsByPredicate().getOrDefault(
                            predicate.predicateId(), List.of());
            anyTargetEvaluation |= !predicateEvaluations.isEmpty();
            PredicateState state = reducePredicate(
                    predicate, predicateEvaluations, supporting, contradicting);
            if (predicate.role() == PredicateRole.CRITICAL) {
                criticalRefuted |= state == PredicateState.REFUTED;
                if (state != PredicateState.SUPPORTED && state != PredicateState.REFUTED) {
                    limitations.add(UNKNOWN_CRITICAL_PREFIX + predicate.predicateId().value());
                }
            }
        }

        boolean gapsClosed = true;
        for (EvidenceGapId gapId : hypothesis.gapIds()) {
            EvidenceGap gap = input.gapsById().get(gapId);
            if (gap.status() != EvidenceGapStatus.CLOSED) {
                gapsClosed = false;
                limitations.add(OPEN_GAP_PREFIX + gapId.value());
            }
        }

        List<ObservationPredicate> critical = input.targetPredicates().stream()
                .filter(value -> value.role() == PredicateRole.CRITICAL)
                .toList();
        boolean allCriticalSupported = !critical.isEmpty() && critical.stream().allMatch(
                predicate -> reducePredicateState(
                        predicate,
                        input.evaluationsByPredicate().getOrDefault(
                                predicate.predicateId(), List.of())) == PredicateState.SUPPORTED);

        HypothesisStatus next = nextStatus(
                hypothesis.status(), criticalRefuted, allCriticalSupported,
                gapsClosed, anyTargetEvaluation, critical.isEmpty());
        if (supporting.stream().anyMatch(contradicting::contains)) {
            throw new IllegalArgumentException(
                    "An Evaluation cannot both support and contradict a Hypothesis");
        }
        return new HypothesisEvaluation(
                hypothesis.hypothesisId(), hypothesis.status(), next,
                sortedIds(supporting), sortedIds(contradicting), sortedLimitations(limitations));
    }

    private static HypothesisStatus nextStatus(
            HypothesisStatus previous,
            boolean criticalRefuted,
            boolean allCriticalSupported,
            boolean gapsClosed,
            boolean anyEvaluation,
            boolean noCriticalPredicates) {
        if (criticalRefuted) {
            return HypothesisStatus.REFUTED;
        }
        if (allCriticalSupported && gapsClosed) {
            return HypothesisStatus.SUPPORTED;
        }
        if (noCriticalPredicates) {
            return previous == HypothesisStatus.SUPPORTED
                    ? HypothesisStatus.INCONCLUSIVE : HypothesisStatus.OPEN;
        }
        if (!anyEvaluation && previous == HypothesisStatus.OPEN) {
            return HypothesisStatus.OPEN;
        }
        return HypothesisStatus.INCONCLUSIVE;
    }

    private static PredicateState reducePredicate(
            ObservationPredicate predicate,
            List<ObservationEvaluation> evaluations,
            Set<ObservationEvaluationId> supporting,
            Set<ObservationEvaluationId> contradicting) {
        for (ObservationEvaluation evaluation : evaluations) {
            if (evaluation.evidenceDisposition() != EvidenceDisposition.CONFIRMATION_ELIGIBLE
                    || evaluation.truth() == ObservationTruth.UNKNOWN) {
                continue;
            }
            HypothesisEffect effect = ObservationEvaluationSemantics.intendedEffect(
                    predicate, evaluation.truth());
            if (effect == HypothesisEffect.SUPPORT) {
                supporting.add(evaluation.evaluationId());
            } else if (effect == HypothesisEffect.REFUTE) {
                contradicting.add(evaluation.evaluationId());
            }
        }
        return reducePredicateState(predicate, evaluations);
    }

    private static PredicateState reducePredicateState(
            ObservationPredicate predicate, List<ObservationEvaluation> evaluations) {
        boolean supported = false;
        for (ObservationEvaluation evaluation : evaluations) {
            if (evaluation.evidenceDisposition() != EvidenceDisposition.CONFIRMATION_ELIGIBLE
                    || evaluation.truth() == ObservationTruth.UNKNOWN) {
                continue;
            }
            HypothesisEffect effect = ObservationEvaluationSemantics.intendedEffect(
                    predicate, evaluation.truth());
            if (effect == HypothesisEffect.REFUTE) {
                return PredicateState.REFUTED;
            }
            if (effect == HypothesisEffect.SUPPORT) {
                supported = true;
            }
        }
        return supported ? PredicateState.SUPPORTED : PredicateState.UNKNOWN;
    }

    private static Inputs validate(
            HypothesisRecord hypothesis,
            List<ObservationPredicate> predicates,
            List<EvidenceGap> gaps,
            List<ObservationEvaluation> evaluations) {
        if (hypothesis == null || predicates == null || gaps == null || evaluations == null) {
            throw new IllegalArgumentException("Reducer inputs must not be null");
        }
        Map<ObservationPredicateId, ObservationPredicate> predicatesById = uniqueMap(
                predicates, ObservationPredicate::predicateId, "predicateId");
        Map<EvidenceGapId, EvidenceGap> gapsById = uniqueMap(
                gaps, EvidenceGap::gapId, "gapId");
        uniqueMap(evaluations, ObservationEvaluation::evaluationId, "evaluationId");

        predicates.forEach(value -> requireIdentity(
                hypothesis, value.caseId(), value.analysisId()));
        gaps.forEach(value -> requireIdentity(hypothesis, value.caseId(), value.analysisId()));
        evaluations.forEach(value -> requireIdentity(
                hypothesis, value.caseId(), value.analysisId()));

        List<ObservationPredicate> targetPredicates = predicates.stream()
                .filter(value -> value.hypothesisId().equals(hypothesis.hypothesisId()))
                .sorted(Comparator.comparing(value -> value.predicateId().value()))
                .toList();
        for (EvidenceGapId gapId : hypothesis.gapIds()) {
            EvidenceGap gap = gapsById.get(gapId);
            if (gap == null || !gap.hypothesisIds().contains(hypothesis.hypothesisId())) {
                throw new IllegalArgumentException("Hypothesis references an invalid Evidence Gap");
            }
        }
        for (ObservationPredicate predicate : targetPredicates) {
            EvidenceGap gap = gapsById.get(predicate.gapId());
            if (gap == null || !hypothesis.gapIds().contains(predicate.gapId())
                    || !gap.predicateIds().contains(predicate.predicateId())) {
                throw new IllegalArgumentException("Predicate is not bound to the Hypothesis Gap");
            }
        }
        Map<ObservationPredicateId, List<ObservationEvaluation>> evaluationsByPredicate =
                evaluations.stream().collect(Collectors.groupingBy(
                        ObservationEvaluation::predicateId,
                        HashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), values -> values.stream()
                                .sorted(Comparator.comparing(value ->
                                        value.evaluationId().value()))
                                .toList())));
        if (evaluationsByPredicate.keySet().stream().anyMatch(id -> !predicatesById.containsKey(id))) {
            throw new IllegalArgumentException("Evaluation references an unknown Predicate");
        }
        evaluations.forEach(evaluation -> requireEvaluationSemantics(
                predicatesById.get(evaluation.predicateId()), evaluation));
        return new Inputs(targetPredicates, gapsById, evaluationsByPredicate);
    }

    private static void requireEvaluationSemantics(
            ObservationPredicate predicate, ObservationEvaluation evaluation) {
        ObservationEvaluationSemantics.requireConsistent(predicate, evaluation);
    }

    private static void requireIdentity(
            HypothesisRecord hypothesis,
            org.example.algorithmdebug.contracts.CaseId caseId,
            org.example.algorithmdebug.contracts.AnalysisId analysisId) {
        if (!hypothesis.caseId().equals(caseId) || !hypothesis.analysisId().equals(analysisId)) {
            throw new IllegalArgumentException("Reducer input identity mismatch");
        }
    }

    private static <K, V> Map<K, V> uniqueMap(
            List<V> values, Function<V, K> identity, String field) {
        HashMap<K, V> result = new HashMap<>();
        for (V value : values) {
            if (value == null || result.putIfAbsent(identity.apply(value), value) != null) {
                throw new IllegalArgumentException("Duplicate or null " + field);
            }
        }
        return Map.copyOf(result);
    }

    private static List<ObservationEvaluationId> sortedIds(
            Iterable<ObservationEvaluationId> values) {
        ArrayList<ObservationEvaluationId> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(Comparator.comparing(ObservationEvaluationId::value));
        return List.copyOf(sorted);
    }

    private static List<String> sortedLimitations(List<String> limitations) {
        List<String> sorted = limitations.stream().distinct().sorted().toList();
        if (sorted.size() > InvestigationLimits.MAX_LIMITATIONS) {
            throw new IllegalArgumentException("Reducer limitations exceed the limit");
        }
        return sorted;
    }

    private enum PredicateState {
        SUPPORTED,
        REFUTED,
        UNKNOWN
    }

    private record Inputs(
            List<ObservationPredicate> targetPredicates,
            Map<EvidenceGapId, EvidenceGap> gapsById,
            Map<ObservationPredicateId, List<ObservationEvaluation>> evaluationsByPredicate) {
    }
}
