package org.example.algorithmdebug.evidence;

import java.util.List;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** VALUE_CHANGED 的有序三值实现。 */
public final class ValueChangedEvaluator implements ObservationOperatorEvaluator {
    @Override
    public ObservationOperator operator() {
        return ObservationOperator.VALUE_CHANGED;
    }

    @Override
    public Result evaluate(ObservationSelector selector, EvidenceView evidence) {
        if (!(selector instanceof ObservationSelector.ValueChanged expected)) {
            return unknown(ObservationErrorCode.OBSERVATION_SELECTOR_UNSUPPORTED);
        }
        return evidence.values(expected.projection())
                .map(values -> evaluate(expected, evidence, values))
                .orElseGet(() -> unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING));
    }

    private Result evaluate(
            ObservationSelector.ValueChanged expected,
            EvidenceView evidence,
            EvidenceView.ValueSeries series) {
        if (!series.ordered() || series.values().size() < 2) {
            return unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING);
        }
        for (int index = 1; index < series.values().size(); index++) {
            if (EvidenceView.scalarEquals(series.values().get(index - 1), expected.before())
                    && EvidenceView.scalarEquals(series.values().get(index), expected.after())) {
                return new Result(ObservationTruth.TRUE, List.of());
            }
        }
        if (evidence.isComplete(series.coverage())) {
            return new Result(ObservationTruth.FALSE, List.of());
        }
        return unknown(ObservationErrorCode.OBSERVATION_COVERAGE_INCOMPLETE);
    }

    private static Result unknown(ObservationErrorCode code) {
        return new Result(ObservationTruth.UNKNOWN, List.of(code.name()));
    }
}
