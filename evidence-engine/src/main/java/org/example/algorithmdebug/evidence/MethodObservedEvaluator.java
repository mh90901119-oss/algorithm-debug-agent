package org.example.algorithmdebug.evidence;

import java.util.List;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** METHOD_OBSERVED 的三值实现。 */
public final class MethodObservedEvaluator implements ObservationOperatorEvaluator {
    @Override
    public ObservationOperator operator() {
        return ObservationOperator.METHOD_OBSERVED;
    }

    @Override
    public Result evaluate(ObservationSelector selector, EvidenceView evidence) {
        if (!(selector instanceof ObservationSelector.MethodObserved expected)) {
            return unsupported();
        }
        if (evidence.observesMethod(expected.methodKey())) {
            return new Result(ObservationTruth.TRUE, List.of());
        }
        if (!evidence.tracksMethod(expected.methodKey())) {
            return unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING);
        }
        if (evidence.isComplete(EvidenceSourceCoverage.COMPLETE)) {
            return new Result(ObservationTruth.FALSE, List.of());
        }
        return unknown(ObservationErrorCode.OBSERVATION_COVERAGE_INCOMPLETE);
    }

    private static Result unsupported() {
        return unknown(ObservationErrorCode.OBSERVATION_SELECTOR_UNSUPPORTED);
    }

    private static Result unknown(ObservationErrorCode code) {
        return new Result(ObservationTruth.UNKNOWN, List.of(code.name()));
    }
}
