package org.example.algorithmdebug.evidence;

import java.util.List;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** PATH_CONTAINS 的有序连续子路径三值实现。 */
public final class PathContainsEvaluator implements ObservationOperatorEvaluator {
    @Override
    public ObservationOperator operator() {
        return ObservationOperator.PATH_CONTAINS;
    }

    @Override
    public Result evaluate(ObservationSelector selector, EvidenceView evidence) {
        if (!(selector instanceof ObservationSelector.PathContains expected)) {
            return unknown(ObservationErrorCode.OBSERVATION_SELECTOR_UNSUPPORTED);
        }
        if (evidence.pathFacts().stream().anyMatch(path ->
                contains(path.methodKeys(), expected.methodKeys()))) {
            return new Result(ObservationTruth.TRUE, List.of());
        }
        if (evidence.pathFacts().isEmpty()) {
            return unknown(ObservationErrorCode.OBSERVATION_VALUE_MISSING);
        }
        if (evidence.isComplete(EvidenceSourceCoverage.COMPLETE)) {
            return new Result(ObservationTruth.FALSE, List.of());
        }
        return unknown(ObservationErrorCode.OBSERVATION_COVERAGE_INCOMPLETE);
    }

    private static boolean contains(List<String> actual, List<String> expected) {
        if (actual.size() < expected.size()) {
            return false;
        }
        for (int start = 0; start <= actual.size() - expected.size(); start++) {
            if (actual.subList(start, start + expected.size()).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static Result unknown(ObservationErrorCode code) {
        return new Result(ObservationTruth.UNKNOWN, List.of(code.name()));
    }
}
