package org.example.algorithmdebug.evidence;

import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** VALUE_EQUALS 的三值实现。 */
public final class ValueEqualsEvaluator implements ObservationOperatorEvaluator {
    @Override
    public ObservationOperator operator() {
        return ObservationOperator.VALUE_EQUALS;
    }

    @Override
    public Result evaluate(ObservationSelector selector, EvidenceView evidence) {
        if (!(selector instanceof ObservationSelector.ValueEquals expected)) {
            return unknown(ObservationErrorCode.OBSERVATION_SELECTOR_UNSUPPORTED);
        }
        return evidence.values(expected.projection())
                .map(values -> evaluate(expected, evidence, values))
                .orElseGet(() -> unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING));
    }

    private Result evaluate(
            ObservationSelector.ValueEquals expected,
            EvidenceView evidence,
            EvidenceView.ValueSeries series) {
        if (series.values().isEmpty()) {
            return unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING);
        }
        ArrayList<ObservationSelector.ScalarValue> distinct = new ArrayList<>();
        for (ObservationSelector.ScalarValue value : series.values()) {
            if (distinct.stream().noneMatch(existing -> EvidenceView.scalarEquals(existing, value))) {
                distinct.add(value);
            }
        }
        if (distinct.size() != 1 || !evidence.isComplete(series.coverage())) {
            return unknown(ObservationErrorCode.OBSERVATION_COVERAGE_INCOMPLETE);
        }
        return new Result(EvidenceView.scalarEquals(distinct.getFirst(), expected.expectedValue())
                ? ObservationTruth.TRUE : ObservationTruth.FALSE, List.of());
    }

    private static Result unknown(ObservationErrorCode code) {
        return new Result(ObservationTruth.UNKNOWN, List.of(code.name()));
    }
}
