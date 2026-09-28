package org.example.algorithmdebug.evidence;

import java.util.List;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** COUNT_COMPARE 的精确计数三值实现。 */
public final class CountCompareEvaluator implements ObservationOperatorEvaluator {
    @Override
    public ObservationOperator operator() {
        return ObservationOperator.COUNT_COMPARE;
    }

    @Override
    public Result evaluate(ObservationSelector selector, EvidenceView evidence) {
        if (!(selector instanceof ObservationSelector.CountCompare expected)) {
            return unknown(ObservationErrorCode.OBSERVATION_SELECTOR_UNSUPPORTED);
        }
        return evidence.count(expected.recordType())
                .map(count -> evaluate(expected, evidence, count))
                .orElseGet(() -> unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING));
    }

    private Result evaluate(
            ObservationSelector.CountCompare expected,
            EvidenceView evidence,
            EvidenceView.CountFact count) {
        if (!count.exact() || !evidence.isComplete(EvidenceSourceCoverage.COMPLETE)) {
            return unknown(ObservationErrorCode.OBSERVATION_COVERAGE_INCOMPLETE);
        }
        long actual = count.count();
        long target = expected.expectedCount();
        boolean matches = switch (expected.comparator()) {
            case EQ -> actual == target;
            case NE -> actual != target;
            case LT -> actual < target;
            case LE -> actual <= target;
            case GT -> actual > target;
            case GE -> actual >= target;
        };
        return new Result(matches ? ObservationTruth.TRUE : ObservationTruth.FALSE, List.of());
    }

    private static Result unknown(ObservationErrorCode code) {
        return new Result(ObservationTruth.UNKNOWN, List.of(code.name()));
    }
}
