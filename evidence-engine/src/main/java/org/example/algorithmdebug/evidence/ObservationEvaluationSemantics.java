package org.example.algorithmdebug.evidence;

import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;

/** Predicate、truth、disposition 与 applied effect 的唯一内部语义实现。 */
final class ObservationEvaluationSemantics {
    private ObservationEvaluationSemantics() {
    }

    static HypothesisEffect intendedEffect(
            ObservationPredicate predicate, ObservationTruth truth) {
        return switch (truth) {
            case TRUE -> predicate.onTrue();
            case FALSE -> predicate.onFalse();
            case UNKNOWN -> predicate.onUnknown();
        };
    }

    static boolean shouldApply(
            ObservationPredicate predicate,
            ObservationTruth truth,
            EvidenceDisposition disposition) {
        return predicate.role() == PredicateRole.CRITICAL
                && disposition == EvidenceDisposition.CONFIRMATION_ELIGIBLE
                && truth != ObservationTruth.UNKNOWN
                && intendedEffect(predicate, truth) != HypothesisEffect.NO_CHANGE;
    }

    static void requireConsistent(
            ObservationPredicate predicate, ObservationEvaluation evaluation) {
        HypothesisEffect intended = intendedEffect(predicate, evaluation.truth());
        boolean expectedApplied = shouldApply(
                predicate, evaluation.truth(), evaluation.evidenceDisposition());
        HypothesisEffect expectedEffect = expectedApplied
                ? intended : HypothesisEffect.NO_CHANGE;
        if (evaluation.effectApplied() != expectedApplied
                || evaluation.appliedEffect() != expectedEffect) {
            throw new IllegalArgumentException(
                    "Evaluation effect contradicts its frozen Predicate");
        }
    }
}
