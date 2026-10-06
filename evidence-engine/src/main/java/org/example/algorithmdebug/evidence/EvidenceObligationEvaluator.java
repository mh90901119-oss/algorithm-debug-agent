package org.example.algorithmdebug.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.EvidenceObligation;
import org.example.algorithmdebug.contracts.coordination.EvidenceObligationKind;
import org.example.algorithmdebug.contracts.coordination.ObligationStatus;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** 从控制视图与调查投影独立计算系统、工具和调查证据义务。 */
public final class EvidenceObligationEvaluator {
    private static final int OBLIGATION_HASH_LENGTH = 24;
    private static final String SYSTEM_STATE_KEY = "system-control-state";
    private static final String SYSTEM_CONTRADICTION_KEY = "system-contradiction-free";
    private static final String SYSTEM_STATE_DESCRIPTION =
            "The projected analysis control state is valid";
    private static final String SYSTEM_CONTRADICTION_DESCRIPTION =
            "No unresolved deterministic contradiction is present";
    private static final String TOOL_DESCRIPTION =
            "The referenced tool postcondition is satisfied";
    private static final String INVESTIGATION_DESCRIPTION =
            "The frozen investigation predicate has confirmation-eligible evaluation";

    /**
     * 计算三层义务；任何工具义务都不会合并或覆盖 Investigation Predicate 义务。
     */
    public List<EvidenceObligation> evaluate(
            AnalysisControlView control, InvestigationState investigation) {
        requireIdentity(control, investigation);
        ArrayList<EvidenceObligation> obligations = new ArrayList<>();
        obligations.add(systemState(control));
        obligations.add(contradictionState(control));
        control.satisfiedObligationIds().stream().sorted().forEach(id -> obligations.add(
                obligation("tool-satisfied:" + id, EvidenceObligationKind.ACTION_POSTCONDITION,
                        ObligationStatus.SATISFIED, TOOL_DESCRIPTION, List.of(id), List.of(),
                        control)));
        control.remainingObligationIds().stream().sorted().forEach(id -> obligations.add(
                obligation("tool-missing:" + id, EvidenceObligationKind.ACTION_POSTCONDITION,
                        ObligationStatus.MISSING, TOOL_DESCRIPTION, List.of(id),
                        List.of(CoordinationErrorCode.COORDINATION_POSTCONDITION_FAILED), control)));

        Map<ObservationPredicateId, List<ObservationEvaluation>> byPredicate =
                investigation.evaluations().stream().collect(Collectors.groupingBy(
                        ObservationEvaluation::predicateId));
        investigation.predicates().stream()
                .sorted(Comparator.comparing(value -> value.predicateId().value()))
                .forEach(predicate -> obligations.add(investigationObligation(
                        control, predicate,
                        byPredicate.getOrDefault(predicate.predicateId(), List.of()))));
        return List.copyOf(obligations);
    }

    private static EvidenceObligation systemState(AnalysisControlView control) {
        boolean valid = control.decision() == ActionDecisionCode.ALLOWED;
        List<CoordinationErrorCode> reasons = valid ? List.of()
                : control.reasonCodes().isEmpty()
                ? List.of(CoordinationErrorCode.COORDINATION_STATE_INVALID)
                : control.reasonCodes();
        return obligation(
                SYSTEM_STATE_KEY, EvidenceObligationKind.SYSTEM_PREREQUISITE,
                valid ? ObligationStatus.SATISFIED : ObligationStatus.UNCHECKABLE,
                SYSTEM_STATE_DESCRIPTION, List.of(), reasons, control);
    }

    private static EvidenceObligation contradictionState(AnalysisControlView control) {
        boolean clear = control.contradictionIds().isEmpty();
        return obligation(
                SYSTEM_CONTRADICTION_KEY, EvidenceObligationKind.SYSTEM_PREREQUISITE,
                clear ? ObligationStatus.SATISFIED : ObligationStatus.CONTRADICTED,
                SYSTEM_CONTRADICTION_DESCRIPTION, control.contradictionIds(),
                clear ? List.of() : List.of(CoordinationErrorCode.CONCLUSION_NOT_ELIGIBLE),
                control);
    }

    private static EvidenceObligation investigationObligation(
            AnalysisControlView control,
            ObservationPredicate predicate,
            List<ObservationEvaluation> evaluations) {
        List<ObservationEvaluation> sorted = evaluations.stream()
                .sorted(Comparator.comparing(value -> value.evaluationId().value()))
                .toList();
        sorted.forEach(value -> ObservationEvaluationSemantics.requireConsistent(
                predicate, value));
        ObligationStatus status;
        List<CoordinationErrorCode> reasons;
        if (sorted.isEmpty()) {
            status = ObligationStatus.MISSING;
            reasons = List.of(CoordinationErrorCode.COORDINATION_PREREQUISITE_MISSING);
        } else if (sorted.stream().anyMatch(value ->
                value.evidenceDisposition() == EvidenceDisposition.CONFIRMATION_ELIGIBLE
                        && value.truth() != ObservationTruth.UNKNOWN
                        && ObservationEvaluationSemantics.intendedEffect(
                                predicate, value.truth()) ==
                        org.example.algorithmdebug.contracts.investigation.HypothesisEffect.REFUTE)) {
            status = ObligationStatus.CONTRADICTED;
            reasons = List.of(CoordinationErrorCode.CONCLUSION_NOT_ELIGIBLE);
        } else if (sorted.stream().anyMatch(value ->
                value.evidenceDisposition() == EvidenceDisposition.CONFIRMATION_ELIGIBLE
                        && value.truth() != ObservationTruth.UNKNOWN)) {
            status = ObligationStatus.SATISFIED;
            reasons = List.of();
        } else {
            status = ObligationStatus.UNCHECKABLE;
            reasons = List.of(CoordinationErrorCode.CONCLUSION_NOT_ELIGIBLE);
        }
        ArrayList<String> references = new ArrayList<>();
        references.add(predicate.predicateId().value());
        sorted.forEach(value -> references.add(value.evaluationId().value()));
        return obligation(
                "investigation:" + predicate.predicateId().value(),
                EvidenceObligationKind.INVESTIGATION_PREDICATE,
                status, INVESTIGATION_DESCRIPTION, references, reasons, control);
    }

    private static EvidenceObligation obligation(
            String stableKey,
            EvidenceObligationKind kind,
            ObligationStatus status,
            String description,
            List<String> references,
            List<CoordinationErrorCode> reasons,
            AnalysisControlView control) {
        String id = "obligation-" + ObservationInputHasher.sha256(stableKey)
                .substring(0, OBLIGATION_HASH_LENGTH);
        return new EvidenceObligation(
                SchemaVersions.EVIDENCE_OBLIGATION, id, control.identity(), kind, status,
                description, references, reasons);
    }

    private static void requireIdentity(
            AnalysisControlView control, InvestigationState investigation) {
        if (control == null || investigation == null) {
            throw new IllegalArgumentException("control and investigation must not be null");
        }
        if (!control.identity().caseId().equals(investigation.caseId())
                || !control.identity().analysisId().equals(investigation.analysisId())) {
            throw new IllegalArgumentException("Control and Investigation identity mismatch");
        }
    }
}
