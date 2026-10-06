package org.example.algorithmdebug.evidence;

import java.util.List;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** RECORD_EXISTS 的三值实现。 */
public final class RecordExistsEvaluator implements ObservationOperatorEvaluator {
    @Override
    public ObservationOperator operator() {
        return ObservationOperator.RECORD_EXISTS;
    }

    @Override
    public Result evaluate(ObservationSelector selector, EvidenceView evidence) {
        if (!(selector instanceof ObservationSelector.RecordExists expected)) {
            return unknown(ObservationErrorCode.OBSERVATION_SELECTOR_UNSUPPORTED);
        }
        return evidence.recordSet(expected.recordType(), expected.fieldPath())
                .map(records -> evaluate(expected, evidence, records))
                .orElseGet(() -> unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING));
    }

    private Result evaluate(
            ObservationSelector.RecordExists expected,
            EvidenceView evidence,
            EvidenceView.RecordSet records) {
        if (records.values().stream().anyMatch(value ->
                EvidenceView.scalarEquals(value, expected.expectedValue()))) {
            return new Result(ObservationTruth.TRUE, List.of());
        }
        if (evidence.isComplete(records.coverage())) {
            return new Result(ObservationTruth.FALSE, List.of());
        }
        return unknown(ObservationErrorCode.OBSERVATION_COVERAGE_INCOMPLETE);
    }

    private static Result unknown(ObservationErrorCode code) {
        return new Result(ObservationTruth.UNKNOWN, List.of(code.name()));
    }
}
