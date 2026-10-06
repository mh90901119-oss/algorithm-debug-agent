package org.example.algorithmdebug.core.coordination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.example.algorithmdebug.casecore.AnalysisArtifactIndex;
import org.example.algorithmdebug.casecore.InvestigationJournalReader;
import org.example.algorithmdebug.contracts.EvidenceEligibilityReason;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;

/**
 * 仅从已校验 Artifact metadata 和 Investigation Journal 派生控制视图，不保存可覆盖状态文件。
 */
public final class AnalysisStateProjector {
    private static final String ALGORITHM_INPUT_ARTIFACT = "ALGORITHM_INPUT";
    private static final String METHOD_CATALOG_ARTIFACT = "METHOD_CATALOG";
    private static final String CODEPATH_PLAN_ARTIFACT = "CODEPATH_PLAN";
    private static final String JDWP_PLAN_ARTIFACT = "JDWP_PLAN";
    private final AnalysisArtifactIndex artifactIndex;
    private final InvestigationSource investigationSource;
    private final InvestigationStateProjector investigationProjector;

    /** Investigation Journal 的只读边界。 */
    @FunctionalInterface
    public interface InvestigationSource {
        /** @return 已校验事件及可恢复 limitation */
        InvestigationJournalReader.Result readValidatedEvents(AnalysisIdentity identity);
    }

    /**
     * @param artifactIndex 已校验且规范排序的 Artifact 元数据索引
     * @param investigationSource Investigation Journal 只读端口
     * @param investigationProjector typed event reducer
     */
    public AnalysisStateProjector(
            AnalysisArtifactIndex artifactIndex,
            InvestigationSource investigationSource,
            InvestigationStateProjector investigationProjector) {
        this.artifactIndex = requireNonNull(artifactIndex, "artifactIndex");
        this.investigationSource = requireNonNull(investigationSource, "investigationSource");
        this.investigationProjector = requireNonNull(
                investigationProjector, "investigationProjector");
    }

    /**
     * 重建 Analysis 当前控制视图；损坏或冲突的 Journal 返回明确拒绝视图。
     */
    public AnalysisControlView project(AnalysisIdentity identity) {
        AnalysisIdentity checkedIdentity = requireNonNull(identity, "identity");
        List<AnalysisArtifactIndex.Entry> artifacts = artifactIndex.forAnalysis(checkedIdentity);
        try {
            InvestigationJournalReader.Result journal = investigationSource.readValidatedEvents(
                    checkedIdentity);
            if (journal.events().isEmpty()) {
                throw new IllegalArgumentException("Investigation ProblemFrame is missing");
            }
            InvestigationState state = investigationProjector.project(journal.events());
            if (!checkedIdentity.caseId().equals(state.caseId())
                    || !checkedIdentity.analysisId().equals(state.analysisId())) {
                throw new IllegalArgumentException("Projected Investigation identity mismatch");
            }
            return cleanView(checkedIdentity, artifacts, journal, state);
        } catch (RuntimeException stateFailure) {
            return failedClosedView(checkedIdentity, artifacts.size());
        }
    }

    private AnalysisControlView cleanView(
            AnalysisIdentity identity,
            List<AnalysisArtifactIndex.Entry> artifacts,
            InvestigationJournalReader.Result journal,
            InvestigationState state) {
        List<String> contradictions = artifacts.stream()
                .filter(value -> value.eligibility().map(eligibility ->
                        eligibility.reasonCodes().contains(
                                EvidenceEligibilityReason.FAILURE_FINGERPRINT_CHANGED.name()))
                        .orElse(false))
                .map(value -> value.artifact().artifactId())
                .sorted()
                .toList();
        List<String> openGaps = state.gaps().stream()
                .filter(value -> value.status() != EvidenceGapStatus.CLOSED)
                .map(value -> value.gapId().value())
                .sorted()
                .toList();
        List<String> supported = state.hypotheses().stream()
                .filter(value -> value.status() == HypothesisStatus.SUPPORTED)
                .map(value -> value.hypothesisId().value())
                .sorted()
                .toList();
        List<String> refuted = state.hypotheses().stream()
                .filter(value -> value.status() == HypothesisStatus.REFUTED)
                .map(value -> value.hypothesisId().value())
                .sorted()
                .toList();
        Set<org.example.algorithmdebug.contracts.investigation.ObservationPredicateId> evaluated =
                state.evaluations().stream().map(ObservationEvaluation::predicateId)
                        .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        List<String> unevaluated = state.predicates().stream()
                .filter(value -> !evaluated.contains(value.predicateId()))
                .map(value -> value.predicateId().value())
                .sorted()
                .toList();
        long revision = Math.addExact(state.lastSequence(), artifacts.size());
        boolean limited = !journal.limitations().isEmpty() || !state.limitations().isEmpty();
        ConclusionStatus terminalEligibility = !supported.isEmpty() && !limited
                ? ConclusionStatus.BOUNDED_HYPOTHESIS
                : ConclusionStatus.MISSING_EVIDENCE;

        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                identity,
                revision,
                AnalysisActionType.ANALYSIS_STATUS,
                ActionDecisionCode.ALLOWED,
                List.of(),
                List.of(),
                List.of(),
                contradictions,
                openGaps,
                supported,
                refuted,
                unevaluated,
                allowedActions(state, artifacts),
                terminalEligibility);
    }

    private AnalysisControlView failedClosedView(AnalysisIdentity identity, int artifactCount) {
        return new AnalysisControlView(
                SchemaVersions.ANALYSIS_CONTROL_VIEW,
                CoordinationPolicyVersions.CURRENT,
                identity,
                artifactCount,
                AnalysisActionType.ANALYSIS_STATUS,
                ActionDecisionCode.REJECTED,
                List.of(CoordinationErrorCode.COORDINATION_STATE_INVALID),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(AnalysisActionType.CASE_INSPECT, AnalysisActionType.ANALYSIS_STATUS),
                ConclusionStatus.MISSING_EVIDENCE);
    }

    private List<AnalysisActionType> allowedActions(
            InvestigationState state, List<AnalysisArtifactIndex.Entry> artifacts) {
        EnumSet<AnalysisActionType> actions = EnumSet.of(
                AnalysisActionType.CASE_INSPECT,
                AnalysisActionType.CASE_AUDIT,
                AnalysisActionType.ARTIFACT_READ,
                AnalysisActionType.EVIDENCE_QUERY,
                AnalysisActionType.ANALYSIS_STATUS,
                AnalysisActionType.ALGORITHM_INPUT_CAPTURE,
                AnalysisActionType.STATIC_ANALYZE,
                AnalysisActionType.INVESTIGATION_UPDATE);
        boolean hasInput = hasArtifactType(artifacts, ALGORITHM_INPUT_ARTIFACT);
        boolean hasCatalog = hasArtifactType(artifacts, METHOD_CATALOG_ARTIFACT);
        if (hasInput) {
            actions.add(AnalysisActionType.RUN_TEST);
        }
        if (hasCatalog) {
            actions.add(AnalysisActionType.SOURCE_QUERY);
        }
        if (hasInput && hasCatalog) {
            actions.add(AnalysisActionType.CODEPATH_PLAN_CREATE);
            actions.add(AnalysisActionType.JDWP_PLAN_CREATE);
        }
        if (hasPlanType(artifacts, CODEPATH_PLAN_ARTIFACT)) {
            actions.add(AnalysisActionType.CODEPATH_COLLECT);
        }
        if (hasPlanType(artifacts, JDWP_PLAN_ARTIFACT)) {
            actions.add(AnalysisActionType.JDWP_COLLECT);
        }
        if (artifacts.stream().anyMatch(value ->
                value.artifact().artifactType().contains("GANTT"))) {
            actions.add(AnalysisActionType.GANTT_INSPECT);
        }
        if (!state.hypotheses().isEmpty()) {
            actions.add(AnalysisActionType.ANALYSIS_FINALIZE);
        }
        List<AnalysisActionType> sorted = new ArrayList<>(actions);
        sorted.sort(Comparator.comparing(Enum::name));
        return List.copyOf(sorted);
    }

    private static boolean hasArtifactType(
            List<AnalysisArtifactIndex.Entry> artifacts, String artifactType) {
        return artifacts.stream().anyMatch(value ->
                artifactType.equals(value.artifact().artifactType()));
    }

    private static boolean hasPlanType(
            List<AnalysisArtifactIndex.Entry> artifacts, String artifactType) {
        return artifacts.stream().anyMatch(value -> value.planId().isPresent()
                && artifactType.equals(value.artifact().artifactType()));
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
