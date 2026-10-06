package org.example.algorithmdebug.evidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.CausalChain;
import org.example.algorithmdebug.contracts.investigation.CausalEdge;
import org.example.algorithmdebug.contracts.investigation.CausalNodeType;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;

/** 确定性校验因果链引用、关键路径、动态资格、反证和竞争假设。 */
public final class CausalChainEligibilityEvaluator {

    /** 因果链不能达到确认等级的稳定原因。 */
    public enum ReasonCode {
        IDENTITY_MISMATCH,
        REFERENCE_MISSING,
        CAUSAL_PATH_INCOMPLETE,
        DYNAMIC_CRITICAL_EDGE_MISSING,
        DYNAMIC_CRITICAL_EDGE_INELIGIBLE,
        DYNAMIC_CRITICAL_EDGE_UNGROUNDED,
        CRITICAL_EDGE_CLASSIFICATION_UNCONFIRMED,
        TARGET_HYPOTHESIS_NOT_SUPPORTED,
        ALTERNATIVE_NOT_REFUTED,
        OPEN_EQUIVALENT_ALTERNATIVE,
        CRITICAL_PREDICATE_MISSING,
        CRITICAL_PREDICATE_FALSE,
        CRITICAL_PREDICATE_UNKNOWN,
        CRITICAL_COVERAGE_INCOMPLETE,
        TARGET_GAP_UNRESOLVED,
        INVESTIGATION_LIMITED
    }

    /** 当前 Analysis 已归档且允许被结论引用的只读目录。 */
    public record ReferenceCatalog(
            AnalysisIdentity identity,
            Map<EvidenceId, EvidenceEligibility> evidence,
            Set<SourceQueryId> sourceQueries,
            Set<String> artifactIds) {
        /** 防御性复制目录并拒绝空键值。 */
        public ReferenceCatalog {
            if (identity == null || evidence == null || sourceQueries == null || artifactIds == null
                    || evidence.entrySet().stream().anyMatch(value ->
                    value.getKey() == null || value.getValue() == null)
                    || sourceQueries.stream().anyMatch(java.util.Objects::isNull)
                    || artifactIds.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("Reference catalog must not contain null values");
            }
            evidence = Map.copyOf(evidence);
            sourceQueries = Set.copyOf(sourceQueries);
            artifactIds = Set.copyOf(artifactIds);
        }
    }

    /**
     * @param allowedStatus 当前因果链允许的最高结论等级
     * @param reasonCodes 等级受限原因
     * @param missingEvidence 缺失或不可解引用的 ID
     * @param acceptedReferenceIds 可被自然语言 Claim 引用的节点/边 ID
     */
    public record Evaluation(
            ConclusionStatus allowedStatus,
            List<ReasonCode> reasonCodes,
            List<String> missingEvidence,
            Set<String> acceptedReferenceIds) {
        /** 校验输出不可为空并冻结集合。 */
        public Evaluation {
            if (allowedStatus == null || reasonCodes == null || missingEvidence == null
                    || acceptedReferenceIds == null) {
                throw new IllegalArgumentException("Causal eligibility output must not be null");
            }
            reasonCodes = List.copyOf(new LinkedHashSet<>(reasonCodes));
            missingEvidence = List.copyOf(new LinkedHashSet<>(missingEvidence));
            acceptedReferenceIds = Set.copyOf(acceptedReferenceIds);
        }
    }

    /** 评估一条 Chain；不读取文件、不调用模型，也不修改调查状态。 */
    public Evaluation evaluate(
            CausalChain chain,
            InvestigationState state,
            ReferenceCatalog catalog) {
        if (chain == null || state == null || catalog == null) {
            throw new IllegalArgumentException("chain, state and catalog must not be null");
        }
        LinkedHashSet<ReasonCode> reasons = new LinkedHashSet<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        LinkedHashSet<String> acceptedReferences = new LinkedHashSet<>();

        if (!chain.caseId().equals(state.caseId())
                || !chain.analysisId().equals(state.analysisId())
                || !chain.caseId().equals(catalog.identity().caseId())
                || !chain.analysisId().equals(catalog.identity().analysisId())) {
            reasons.add(ReasonCode.IDENTITY_MISMATCH);
            missing.add(chain.causalChainId().value());
        }
        chain.nodes().forEach(value -> {
            acceptedReferences.add(value.nodeId());
            validateReferences(
                    value.evidenceIds(), value.sourceQueryIds(), catalog, reasons, missing);
        });
        chain.edges().forEach(value -> {
            acceptedReferences.add(value.edgeId());
            validateReferences(
                    value.evidenceIds(), value.sourceQueryIds(), catalog, reasons, missing);
        });
        if (!hasRequiredCriticalPath(chain)) {
            reasons.add(ReasonCode.CAUSAL_PATH_INCOMPLETE);
        }
        validateCriticalEdges(chain, state, catalog, reasons);
        validateHypotheses(state, reasons);
        validateSupportedPredicates(state, reasons, missing);
        if (!state.limitations().isEmpty()) {
            reasons.add(ReasonCode.INVESTIGATION_LIMITED);
            missing.addAll(state.limitations());
        }

        ConclusionStatus allowed = reasons.contains(ReasonCode.IDENTITY_MISMATCH)
                || reasons.contains(ReasonCode.REFERENCE_MISSING)
                ? ConclusionStatus.MISSING_EVIDENCE
                : reasons.isEmpty()
                        ? ConclusionStatus.CONFIRMED
                        : ConclusionStatus.BOUNDED_HYPOTHESIS;
        if (allowed == ConclusionStatus.MISSING_EVIDENCE) {
            acceptedReferences.clear();
        }
        return new Evaluation(
                allowed, new ArrayList<>(reasons), new ArrayList<>(missing), acceptedReferences);
    }

    private static void validateReferences(
            List<EvidenceId> evidenceIds,
            List<SourceQueryId> sourceQueryIds,
            ReferenceCatalog catalog,
            Set<ReasonCode> reasons,
            Set<String> missing) {
        evidenceIds.stream().filter(value -> !catalog.evidence().containsKey(value))
                .forEach(value -> {
                    reasons.add(ReasonCode.REFERENCE_MISSING);
                    missing.add(value.value());
                });
        sourceQueryIds.stream().filter(value -> !catalog.sourceQueries().contains(value))
                .forEach(value -> {
                    reasons.add(ReasonCode.REFERENCE_MISSING);
                    missing.add(value.value());
                });
    }

    private static boolean hasRequiredCriticalPath(CausalChain chain) {
        Map<String, List<String>> adjacency = new HashMap<>();
        chain.edges().stream().filter(CausalEdge::critical).forEach(value ->
                adjacency.computeIfAbsent(value.fromNodeId(), ignored -> new ArrayList<>())
                        .add(value.toNodeId()));
        Set<String> sourceNodes = nodeIds(chain, CausalNodeType.SOURCE_MECHANISM);
        Set<String> middleNodes = new HashSet<>(nodeIds(chain, CausalNodeType.RUNTIME_STATE));
        middleNodes.addAll(nodeIds(chain, CausalNodeType.DECISION));
        Set<String> symptomNodes = nodeIds(chain, CausalNodeType.SYMPTOM);
        return sourceNodes.stream().anyMatch(source -> middleNodes.stream().anyMatch(middle ->
                reachable(source, middle, adjacency)
                        && symptomNodes.stream().anyMatch(symptom ->
                        reachable(middle, symptom, adjacency))));
    }

    private static Set<String> nodeIds(CausalChain chain, CausalNodeType type) {
        return chain.nodes().stream().filter(value -> value.type() == type)
                .map(value -> value.nodeId()).collect(java.util.stream.Collectors.toSet());
    }

    private static boolean reachable(
            String source, String target, Map<String, List<String>> adjacency) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        pending.add(source);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(target)) {
                return true;
            }
            pending.addAll(adjacency.getOrDefault(current, List.of()));
        }
        return false;
    }

    private static void validateCriticalEdges(
            CausalChain chain,
            InvestigationState state,
            ReferenceCatalog catalog,
            Set<ReasonCode> reasons) {
        Set<EvidenceId> groundedEvidence = state.evaluations().stream()
                .filter(value -> value.evidenceDisposition()
                        == EvidenceDisposition.CONFIRMATION_ELIGIBLE)
                .filter(value -> value.sourceCoverage() == EvidenceSourceCoverage.COMPLETE)
                .filter(value -> value.truth() != ObservationTruth.UNKNOWN)
                .flatMap(value -> value.evidenceIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        for (CausalEdge edge : chain.edges()) {
            if (!edge.critical()) {
                continue;
            }
            if (edge.classification()
                    != org.example.algorithmdebug.contracts.ClaimClassification.CONFIRMED_FACT
                    && edge.classification()
                    != org.example.algorithmdebug.contracts.ClaimClassification.VALIDATOR_CONCLUSION) {
                reasons.add(ReasonCode.CRITICAL_EDGE_CLASSIFICATION_UNCONFIRMED);
            }
            if (edge.evidenceIds().isEmpty()) {
                reasons.add(ReasonCode.DYNAMIC_CRITICAL_EDGE_MISSING);
                continue;
            }
            boolean confirmationEligible = edge.evidenceIds().stream()
                    .map(catalog.evidence()::get)
                    .anyMatch(value -> value != null && value.confirmationEligible());
            if (!confirmationEligible) {
                reasons.add(ReasonCode.DYNAMIC_CRITICAL_EDGE_INELIGIBLE);
            }
            if (edge.evidenceIds().stream().noneMatch(groundedEvidence::contains)) {
                reasons.add(ReasonCode.DYNAMIC_CRITICAL_EDGE_UNGROUNDED);
            }
        }
    }

    private static void validateHypotheses(
            InvestigationState state, Set<ReasonCode> reasons) {
        var grounded = state.hypotheses().stream().filter(value ->
                !value.sourceAnchorRefs().isEmpty()
                        || !value.supportingEvaluationIds().isEmpty()
                        || !value.contradictingEvaluationIds().isEmpty()).toList();
        var supported = grounded.stream()
                .filter(value -> value.status() == HypothesisStatus.SUPPORTED).toList();
        long refuted = grounded.stream()
                .filter(value -> value.status() == HypothesisStatus.REFUTED).count();
        boolean openAlternative = grounded.stream().anyMatch(value ->
                value.status() == HypothesisStatus.OPEN
                        || value.status() == HypothesisStatus.INCONCLUSIVE);
        if (supported.isEmpty()) {
            reasons.add(ReasonCode.TARGET_HYPOTHESIS_NOT_SUPPORTED);
        }
        if (grounded.size() < 2 || refuted < 1) {
            reasons.add(ReasonCode.ALTERNATIVE_NOT_REFUTED);
        }
        if (openAlternative) {
            reasons.add(ReasonCode.OPEN_EQUIVALENT_ALTERNATIVE);
        }
        Set<org.example.algorithmdebug.contracts.investigation.HypothesisId> supportedIds =
                supported.stream().map(value -> value.hypothesisId())
                        .collect(java.util.stream.Collectors.toSet());
        state.gaps().stream()
                .filter(value -> value.hypothesisIds().stream().anyMatch(supportedIds::contains))
                .filter(value -> value.status() != EvidenceGapStatus.CLOSED)
                .forEach(value -> reasons.add(ReasonCode.TARGET_GAP_UNRESOLVED));
    }

    private static void validateSupportedPredicates(
            InvestigationState state,
            Set<ReasonCode> reasons,
            Set<String> missing) {
        Set<org.example.algorithmdebug.contracts.investigation.HypothesisId> activeTargets =
                state.hypotheses().stream()
                        .filter(value -> value.status() != HypothesisStatus.REFUTED)
                        .map(value -> value.hypothesisId())
                        .collect(java.util.stream.Collectors.toSet());
        state.predicates().stream()
                .filter(value -> value.role() == PredicateRole.CRITICAL)
                .filter(value -> activeTargets.contains(value.hypothesisId()))
                .forEach(predicate -> validatePredicate(
                        predicate, state.evaluations(), reasons, missing));
    }

    private static void validatePredicate(
            ObservationPredicate predicate,
            List<ObservationEvaluation> evaluations,
            Set<ReasonCode> reasons,
            Set<String> missing) {
        ObservationEvaluation latest = evaluations.stream()
                .filter(value -> value.predicateId().equals(predicate.predicateId()))
                .max(Comparator.comparing(ObservationEvaluation::evaluatedAt)
                        .thenComparing(value -> value.evaluationId().value()))
                .orElse(null);
        if (latest == null) {
            reasons.add(ReasonCode.CRITICAL_PREDICATE_MISSING);
            missing.add(predicate.predicateId().value());
            return;
        }
        if (latest.truth() == ObservationTruth.FALSE) {
            reasons.add(ReasonCode.CRITICAL_PREDICATE_FALSE);
        } else if (latest.truth() == ObservationTruth.UNKNOWN) {
            reasons.add(ReasonCode.CRITICAL_PREDICATE_UNKNOWN);
        }
        if (latest.sourceCoverage() != EvidenceSourceCoverage.COMPLETE) {
            reasons.add(ReasonCode.CRITICAL_COVERAGE_INCOMPLETE);
        }
        if (latest.evidenceDisposition() != EvidenceDisposition.CONFIRMATION_ELIGIBLE) {
            reasons.add(ReasonCode.DYNAMIC_CRITICAL_EDGE_INELIGIBLE);
        }
    }
}
