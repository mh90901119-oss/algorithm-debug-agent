package org.example.algorithmdebug.core.coordination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluationId;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;

/** 从严格有序的 typed Investigation Event 重建不可变调查状态。 */
public final class InvestigationStateProjector {
    private static final String GAP_LIMITATION_PREFIX = "INVESTIGATION_SEQUENCE_GAP:";
    private static final int MAX_EVENTS = 4_096;

    /**
     * 重放事件。输入顺序就是 Ledger 顺序，不接受调用方悄悄重排；缺号保留 limitation。
     *
     * @param events 已完成 Schema、哈希和归属校验的事件
     * @return 确定性调查状态
     */
    public InvestigationState project(List<InvestigationEvent> events) {
        List<InvestigationEvent> checked = List.copyOf(requireNonNull(events, "events"));
        if (checked.isEmpty() || checked.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Investigation events must not be empty or contain null");
        }
        if (checked.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("Investigation events exceed maximum count " + MAX_EVENTS);
        }
        if (!(checked.getFirst() instanceof InvestigationEvent.ProblemFrameDefined first)
                || first.sequence() != 1) {
            throw new IllegalArgumentException("ProblemFrame must be the first event at sequence 1");
        }

        CaseId caseId = first.caseId();
        AnalysisId analysisId = first.analysisId();
        ProblemFrame problemFrame = null;
        Map<HypothesisId, HypothesisRecord> hypotheses = new LinkedHashMap<>();
        Map<EvidenceGapId, EvidenceGap> gaps = new LinkedHashMap<>();
        Map<ObservationPredicateId, ObservationPredicate> predicates = new LinkedHashMap<>();
        Map<ObservationEvaluationId, ObservationEvaluation> evaluations = new LinkedHashMap<>();
        Set<String> eventIds = new HashSet<>();
        LinkedHashSet<String> limitations = new LinkedHashSet<>();
        long previousSequence = 0;

        for (InvestigationEvent event : checked) {
            requireIdentity(caseId, analysisId, event);
            if (event.sequence() <= previousSequence) {
                throw new IllegalArgumentException("Investigation events are not strictly ordered");
            }
            addGapLimitations(previousSequence, event.sequence(), limitations);
            previousSequence = event.sequence();
            if (!eventIds.add(event.eventId())) {
                throw new IllegalArgumentException("Duplicate Investigation eventId");
            }

            if (event instanceof InvestigationEvent.ProblemFrameDefined value) {
                if (problemFrame != null || event.sequence() != 1) {
                    throw new IllegalArgumentException("ProblemFrame must be unique and first");
                }
                problemFrame = value.problemFrame();
            } else if (event instanceof InvestigationEvent.HypothesisAdded value) {
                putUnique(hypotheses, value.hypothesis().hypothesisId(), value.hypothesis(), "Hypothesis");
            } else if (event instanceof InvestigationEvent.EvidenceGapAdded value) {
                requireHypotheses(hypotheses, value.gap().hypothesisIds());
                putUnique(gaps, value.gap().gapId(), value.gap(), "EvidenceGap");
            } else if (event instanceof InvestigationEvent.PredicateRegistered value) {
                registerPredicate(hypotheses, gaps, predicates, value.predicate());
            } else if (event instanceof InvestigationEvent.PlanBound value) {
                validateBinding(hypotheses, gaps, predicates, value.binding());
            } else if (event instanceof InvestigationEvent.ObservationEvaluated value) {
                applyObservation(hypotheses, predicates, evaluations, limitations, value.evaluation());
            } else if (event instanceof InvestigationEvent.GapStatusChanged value) {
                applyGapStatus(gaps, value);
            } else if (event instanceof InvestigationEvent.HypothesisStatusChanged value) {
                applyHypothesisStatus(hypotheses, predicates, evaluations, limitations, value);
            } else {
                throw new IllegalArgumentException("Unsupported Investigation event type");
            }
        }

        if (problemFrame == null) {
            throw new IllegalArgumentException("ProblemFrame is missing");
        }
        return new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE,
                caseId,
                analysisId,
                problemFrame,
                sorted(hypotheses, value -> value.hypothesisId().value()),
                sorted(gaps, value -> value.gapId().value()),
                sorted(predicates, value -> value.predicateId().value()),
                sorted(evaluations, value -> value.evaluationId().value()),
                previousSequence,
                List.copyOf(limitations));
    }

    /** @return 尚无 Evaluation 的 CRITICAL Predicate ID，按 ID 稳定排序 */
    public List<ObservationPredicateId> unevaluatedCriticalPredicateIds(InvestigationState state) {
        InvestigationState checked = requireNonNull(state, "state");
        Set<ObservationPredicateId> evaluated = checked.evaluations().stream()
                .map(ObservationEvaluation::predicateId).collect(java.util.stream.Collectors.toSet());
        return checked.predicates().stream()
                .filter(value -> value.role() == PredicateRole.CRITICAL)
                .map(ObservationPredicate::predicateId)
                .filter(value -> !evaluated.contains(value))
                .sorted(Comparator.comparing(ObservationPredicateId::value))
                .toList();
    }

    private static void registerPredicate(
            Map<HypothesisId, HypothesisRecord> hypotheses,
            Map<EvidenceGapId, EvidenceGap> gaps,
            Map<ObservationPredicateId, ObservationPredicate> predicates,
            ObservationPredicate predicate) {
        HypothesisRecord hypothesis = requireEntry(
                hypotheses, predicate.hypothesisId(), "Predicate Hypothesis");
        EvidenceGap gap = requireEntry(gaps, predicate.gapId(), "Predicate EvidenceGap");
        if (!gap.hypothesisIds().contains(predicate.hypothesisId())) {
            throw new IllegalArgumentException("Predicate Hypothesis is not associated with its Gap");
        }
        putUnique(predicates, predicate.predicateId(), predicate, "ObservationPredicate");
        if (!gap.predicateIds().contains(predicate.predicateId())) {
            List<ObservationPredicateId> predicateIds = new ArrayList<>(gap.predicateIds());
            predicateIds.add(predicate.predicateId());
            gaps.put(gap.gapId(), copyGap(gap, gap.status(), predicateIds));
        }
        if (predicate.role() == PredicateRole.CRITICAL
                && hypothesis.status() == HypothesisStatus.SUPPORTED) {
            hypotheses.put(hypothesis.hypothesisId(), copyHypothesis(
                    hypothesis, HypothesisStatus.INCONCLUSIVE,
                    hypothesis.supportingEvaluationIds(), hypothesis.contradictingEvaluationIds()));
        }
    }

    private static void validateBinding(
            Map<HypothesisId, HypothesisRecord> hypotheses,
            Map<EvidenceGapId, EvidenceGap> gaps,
            Map<ObservationPredicateId, ObservationPredicate> predicates,
            org.example.algorithmdebug.contracts.investigation.InvestigationBinding binding) {
        EvidenceGap gap = requireEntry(gaps, binding.gapId(), "Binding EvidenceGap");
        if (gap.status() != EvidenceGapStatus.OPEN) {
            throw new IllegalArgumentException("Plan binding requires an OPEN EvidenceGap");
        }
        requireHypotheses(hypotheses, binding.hypothesisIds());
        boolean critical = false;
        for (ObservationPredicateId predicateId : binding.predicateIds()) {
            ObservationPredicate predicate = requireEntry(
                    predicates, predicateId, "Binding Predicate");
            if (!predicate.gapId().equals(binding.gapId())
                    || !binding.hypothesisIds().contains(predicate.hypothesisId())) {
                throw new IllegalArgumentException("Binding Predicate identity mismatch");
            }
            critical |= predicate.role() == PredicateRole.CRITICAL;
        }
        if (!critical) {
            throw new IllegalArgumentException("Plan binding requires a CRITICAL Predicate");
        }
    }

    private static void applyObservation(
            Map<HypothesisId, HypothesisRecord> hypotheses,
            Map<ObservationPredicateId, ObservationPredicate> predicates,
            Map<ObservationEvaluationId, ObservationEvaluation> evaluations,
            Set<String> limitations,
            ObservationEvaluation evaluation) {
        ObservationPredicate predicate = requireEntry(
                predicates, evaluation.predicateId(), "Evaluation Predicate");
        HypothesisEffect declaredEffect = switch (evaluation.truth()) {
            case TRUE -> predicate.onTrue();
            case FALSE -> predicate.onFalse();
            case UNKNOWN -> predicate.onUnknown();
        };
        if (evaluation.effectApplied() && evaluation.appliedEffect() != declaredEffect) {
            throw new IllegalArgumentException("Evaluation effect differs from frozen Predicate");
        }
        putUnique(evaluations, evaluation.evaluationId(), evaluation, "ObservationEvaluation");
        limitations.addAll(evaluation.limitations());
        HypothesisRecord hypothesis = requireEntry(
                hypotheses, predicate.hypothesisId(), "Evaluation Hypothesis");
        if (predicate.role() == PredicateRole.CRITICAL
                && evaluation.truth() == ObservationTruth.UNKNOWN
                && hypothesis.status() == HypothesisStatus.SUPPORTED) {
            hypotheses.put(hypothesis.hypothesisId(), copyHypothesis(
                    hypothesis, HypothesisStatus.INCONCLUSIVE,
                    hypothesis.supportingEvaluationIds(), hypothesis.contradictingEvaluationIds()));
        }
    }

    private static void applyGapStatus(
            Map<EvidenceGapId, EvidenceGap> gaps,
            InvestigationEvent.GapStatusChanged event) {
        EvidenceGap gap = requireEntry(gaps, event.gapId(), "EvidenceGap transition");
        if (gap.status() != event.previousStatus()
                || !allowedGapTransition(event.previousStatus(), event.newStatus())) {
            throw new IllegalArgumentException("Illegal EvidenceGap status transition");
        }
        gaps.put(gap.gapId(), copyGap(gap, event.newStatus(), gap.predicateIds()));
    }

    private static void applyHypothesisStatus(
            Map<HypothesisId, HypothesisRecord> hypotheses,
            Map<ObservationPredicateId, ObservationPredicate> predicates,
            Map<ObservationEvaluationId, ObservationEvaluation> evaluations,
            Set<String> limitations,
            InvestigationEvent.HypothesisStatusChanged event) {
        HypothesisRecord hypothesis = requireEntry(
                hypotheses, event.hypothesisId(), "Hypothesis transition");
        if (hypothesis.status() != event.previousStatus()) {
            throw new IllegalArgumentException("Hypothesis transition previousStatus is stale");
        }
        if (hypothesis.status() == HypothesisStatus.REFUTED
                && event.newStatus() != HypothesisStatus.REFUTED) {
            throw new IllegalArgumentException("REFUTED Hypothesis is terminal");
        }
        requireEvaluationReferences(
                event.hypothesisId(), event.evaluation().supportingEvaluationIds(),
                HypothesisEffect.SUPPORT, predicates, evaluations);
        requireEvaluationReferences(
                event.hypothesisId(), event.evaluation().contradictingEvaluationIds(),
                HypothesisEffect.REFUTE, predicates, evaluations);
        limitations.addAll(event.evaluation().limitations());
        hypotheses.put(hypothesis.hypothesisId(), copyHypothesis(
                hypothesis,
                event.newStatus(),
                event.evaluation().supportingEvaluationIds(),
                event.evaluation().contradictingEvaluationIds()));
    }

    private static void requireEvaluationReferences(
            HypothesisId hypothesisId,
            List<ObservationEvaluationId> ids,
            HypothesisEffect expectedEffect,
            Map<ObservationPredicateId, ObservationPredicate> predicates,
            Map<ObservationEvaluationId, ObservationEvaluation> evaluations) {
        for (ObservationEvaluationId id : ids) {
            ObservationEvaluation evaluation = requireEntry(
                    evaluations, id, "Hypothesis Evaluation");
            ObservationPredicate predicate = requireEntry(
                    predicates, evaluation.predicateId(), "Hypothesis Evaluation Predicate");
            if (!predicate.hypothesisId().equals(hypothesisId)
                    || !evaluation.effectApplied()
                    || evaluation.appliedEffect() != expectedEffect) {
                throw new IllegalArgumentException(
                        "Hypothesis Evaluation reference has the wrong effect or identity");
            }
        }
    }

    private static boolean allowedGapTransition(
            EvidenceGapStatus previous, EvidenceGapStatus next) {
        return switch (previous) {
            case OPEN -> next == EvidenceGapStatus.PLANNED
                    || next == EvidenceGapStatus.UNRESOLVED
                    || next == EvidenceGapStatus.CLOSED;
            case PLANNED -> next == EvidenceGapStatus.OBSERVED
                    || next == EvidenceGapStatus.UNRESOLVED
                    || next == EvidenceGapStatus.CLOSED;
            case OBSERVED -> next == EvidenceGapStatus.UNRESOLVED
                    || next == EvidenceGapStatus.CLOSED;
            case UNRESOLVED -> next == EvidenceGapStatus.PLANNED
                    || next == EvidenceGapStatus.CLOSED;
            case CLOSED -> false;
        };
    }

    private static void addGapLimitations(
            long previous, long current, Set<String> limitations) {
        long missing = current - previous - 1;
        if (missing > InvestigationLimits.MAX_LIMITATIONS - limitations.size()) {
            throw new IllegalArgumentException("Investigation sequence gap exceeds limitation budget");
        }
        for (long sequence = previous + 1; sequence < current; sequence++) {
            limitations.add(GAP_LIMITATION_PREFIX + sequence);
        }
    }

    private static HypothesisRecord copyHypothesis(
            HypothesisRecord source,
            HypothesisStatus status,
            List<ObservationEvaluationId> supporting,
            List<ObservationEvaluationId> contradicting) {
        return new HypothesisRecord(
                source.schemaVersion(), source.hypothesisId(), source.caseId(), source.analysisId(),
                source.statement(), status, source.sourceAnchorRefs(), supporting, contradicting,
                source.gapIds(), source.createdAt());
    }

    private static EvidenceGap copyGap(
            EvidenceGap source,
            EvidenceGapStatus status,
            List<ObservationPredicateId> predicateIds) {
        return new EvidenceGap(
                source.schemaVersion(), source.gapId(), source.caseId(), source.analysisId(),
                source.question(), status, source.hypothesisIds(), predicateIds, source.createdAt());
    }

    private static void requireIdentity(
            CaseId caseId, AnalysisId analysisId, InvestigationEvent event) {
        if (!caseId.equals(event.caseId()) || !analysisId.equals(event.analysisId())) {
            throw new IllegalArgumentException("Investigation event identity mismatch");
        }
    }

    private static void requireHypotheses(
            Map<HypothesisId, HypothesisRecord> hypotheses, List<HypothesisId> ids) {
        ids.forEach(id -> requireEntry(hypotheses, id, "Hypothesis"));
    }

    private static <K, V> void putUnique(Map<K, V> values, K key, V value, String label) {
        if (values.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("Duplicate " + label + " identity");
        }
    }

    private static <K, V> V requireEntry(Map<K, V> values, K key, String label) {
        V value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(label + " does not exist");
        }
        return value;
    }

    private static <K, V> List<V> sorted(
            Map<K, V> values, java.util.function.Function<V, String> identity) {
        return values.values().stream().sorted(Comparator.comparing(identity)).toList();
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
