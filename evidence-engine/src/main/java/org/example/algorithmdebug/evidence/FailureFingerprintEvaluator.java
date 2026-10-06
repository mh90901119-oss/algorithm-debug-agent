package org.example.algorithmdebug.evidence;

import java.util.List;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** FAILURE_FINGERPRINT_MATCHES 的三值实现。 */
public final class FailureFingerprintEvaluator implements ObservationOperatorEvaluator {
    @Override
    public ObservationOperator operator() {
        return ObservationOperator.FAILURE_FINGERPRINT_MATCHES;
    }

    @Override
    public Result evaluate(ObservationSelector selector, EvidenceView evidence) {
        if (!(selector instanceof ObservationSelector.FailureFingerprintMatches)) {
            return new Result(ObservationTruth.UNKNOWN,
                    List.of(ObservationErrorCode.OBSERVATION_SELECTOR_UNSUPPORTED.name()));
        }
        return switch (evidence.failureComparisonOutcome()) {
            case MATCHED -> new Result(ObservationTruth.TRUE, List.of());
            case CHANGED -> new Result(ObservationTruth.FALSE, List.of());
            case INCOMPARABLE, NOT_COMPARED -> new Result(
                    ObservationTruth.UNKNOWN,
                    List.of(ObservationErrorCode.OBSERVATION_COVERAGE_INCOMPLETE.name()));
        };
    }
}
