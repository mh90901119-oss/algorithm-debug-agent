package org.example.algorithmdebug.plan;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathMethodSelection;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;

/** 从当前 InvestigationState 解析引用，并验证 Collector 能确定性计算每个 Predicate。 */
final class InvestigationBindingValidator {
    private static final Set<String> CODEPATH_RECORD_TYPES = Set.of(
            "CODEPATH_INVOCATION", "METHOD_PATH_SUMMARY");
    private static final Set<String> JDWP_RECORD_TYPES = Set.of("JDWP_SNAPSHOT");

    InvestigationBinding validateCodePath(
            InvestigationState state,
            InvestigationBindingRequest request,
            List<CodePathMethodSelection> selections,
            CodePathCaptureMode captureMode) {
        Resolved resolved = resolve(state, request);
        Set<String> methodKeys = selections.stream()
                .map(value -> value.selector().methodKey()).collect(Collectors.toUnmodifiableSet());
        Set<String> projections = selections.stream()
                .flatMap(value -> value.projections().stream())
                .map(value -> value.name()).collect(Collectors.toUnmodifiableSet());
        for (ObservationPredicate predicate : resolved.predicates()) {
            ObservationSelector selector = predicate.selector();
            boolean supported = switch (selector) {
                case ObservationSelector.MethodObserved value -> methodKeys.contains(value.methodKey());
                case ObservationSelector.PathContains value ->
                        methodKeys.containsAll(value.methodKeys());
                case ObservationSelector.ValueEquals value ->
                        projections.contains(value.projection());
                case ObservationSelector.ValueChanged value ->
                        captureMode == CodePathCaptureMode.TRACE
                                && projections.contains(value.projection());
                case ObservationSelector.RecordExists value ->
                        CODEPATH_RECORD_TYPES.contains(value.recordType());
                case ObservationSelector.CountCompare value ->
                        CODEPATH_RECORD_TYPES.contains(value.recordType());
                case ObservationSelector.FailureFingerprintMatches ignored -> true;
            };
            requireSupported(predicate, supported, "CodePath");
        }
        return resolved.binding();
    }

    InvestigationBinding validateJdwp(
            InvestigationState state,
            InvestigationBindingRequest request,
            List<JdwpTracepointSpec> tracepoints) {
        Resolved resolved = resolve(state, request);
        Set<String> valuePaths = tracepoints.stream()
                .flatMap(value -> value.capture().valuePaths().stream())
                .collect(Collectors.toUnmodifiableSet());
        int maximumCapturedHits = tracepoints.stream()
                .mapToInt(JdwpTracepointSpec::maxCapturedHits).sum();
        for (ObservationPredicate predicate : resolved.predicates()) {
            ObservationSelector selector = predicate.selector();
            boolean supported = switch (selector) {
                case ObservationSelector.ValueEquals value ->
                        valuePaths.contains(value.projection());
                case ObservationSelector.ValueChanged value ->
                        maximumCapturedHits >= 2 && valuePaths.contains(value.projection());
                case ObservationSelector.RecordExists value ->
                        JDWP_RECORD_TYPES.contains(value.recordType());
                case ObservationSelector.CountCompare value ->
                        JDWP_RECORD_TYPES.contains(value.recordType());
                case ObservationSelector.FailureFingerprintMatches ignored -> true;
                case ObservationSelector.MethodObserved ignored -> false;
                case ObservationSelector.PathContains ignored -> false;
            };
            requireSupported(predicate, supported, "JDWP");
        }
        return resolved.binding();
    }

    private Resolved resolve(InvestigationState state, InvestigationBindingRequest request) {
        if (state == null || request == null) {
            throw new IllegalArgumentException("state and investigation request must not be null");
        }
        Map<EvidenceGapId, EvidenceGap> gaps = state.gaps().stream().collect(
                Collectors.toUnmodifiableMap(EvidenceGap::gapId, Function.identity()));
        Map<HypothesisId, HypothesisRecord> hypotheses = state.hypotheses().stream().collect(
                Collectors.toUnmodifiableMap(HypothesisRecord::hypothesisId, Function.identity()));
        Map<ObservationPredicateId, ObservationPredicate> predicates =
                state.predicates().stream().collect(
                Collectors.toUnmodifiableMap(
                        ObservationPredicate::predicateId, Function.identity()));
        EvidenceGap gap = gaps.get(request.gapId());
        if (gap == null) {
            throw new PlanCompilationException("The selected EvidenceGap does not exist");
        }
        List<HypothesisRecord> selectedHypotheses = request.hypothesisIds().stream()
                .map(id -> hypotheses.get(id))
                .toList();
        if (selectedHypotheses.stream().anyMatch(value -> value == null)) {
            throw new PlanCompilationException("A selected Hypothesis does not exist");
        }
        List<ObservationPredicate> selectedPredicates = request.predicateIds().stream()
                .map(id -> predicates.get(id))
                .toList();
        if (selectedPredicates.stream().anyMatch(value -> value == null)) {
            throw new PlanCompilationException("A selected Predicate does not exist");
        }
        try {
            return new Resolved(
                    InvestigationBinding.bind(
                            gap, selectedHypotheses, selectedPredicates,
                            request.basedOnEvidenceIds()),
                    selectedPredicates);
        } catch (IllegalArgumentException failure) {
            throw new PlanCompilationException(
                    "The investigation binding is invalid: " + failure.getMessage(), failure);
        }
    }

    private static void requireSupported(
            ObservationPredicate predicate, boolean supported, String collector) {
        if (!supported) {
            throw new PlanCompilationException(
                    collector + " cannot evaluate Predicate "
                            + predicate.predicateId().value() + " from the selected capture");
        }
    }

    private record Resolved(
            InvestigationBinding binding, List<ObservationPredicate> predicates) {
    }
}
