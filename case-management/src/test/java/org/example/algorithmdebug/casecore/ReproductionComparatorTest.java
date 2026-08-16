package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReproductionComparatorTest {

    private static final String RAW_A = "a".repeat(64);
    private static final String RAW_B = "b".repeat(64);
    private static final String GANTT_A = "c".repeat(64);
    private static final String GANTT_B = "d".repeat(64);
    private static final String FAILURE_A = "e".repeat(64);
    private static final String FAILURE_B = "f".repeat(64);

    private final ReproductionComparator comparator = new ReproductionComparator();

    @Test
    void matchesSameGanttEvenWhenRawFormattingHashChanged() {
        ReproductionComparator.Result result = comparator.compare(
                gantt("case-1", "context-1", "run-1", RAW_A, GANTT_A),
                gantt("case-1", "context-1", "run-2", RAW_B, GANTT_A),
                ReproductionComparator.Scope.SAME_CONTEXT);

        assertEquals(ComparisonOutcome.MATCHED, result.outcome());
        assertEquals(List.of(), result.changedDimensions());
        assertEquals(
                "Baseline MATCHED; scope=SAME_CONTEXT; referenceRunId=run-1; changedDimensions=NONE",
                result.summary());
    }

    @Test
    void reportsGanttContentAndExistenceChanges() {
        assertChanged(List.of("GANTT"),
                gantt("case-1", "context-1", "run-1", RAW_A, GANTT_A),
                gantt("case-1", "context-1", "run-2", RAW_B, GANTT_B));
        assertChanged(List.of("GANTT", "TARGET_FAILURE"),
                gantt("case-1", "context-1", "run-1", RAW_A, GANTT_A),
                failure("case-1", "context-1", "run-2", FAILURE_A));
    }

    @Test
    void comparesFailureFingerprint() {
        ReproductionComparator.Result matched = comparator.compare(
                failure("case-1", "context-1", "run-1", FAILURE_A),
                failure("case-1", "context-1", "run-2", FAILURE_A),
                ReproductionComparator.Scope.SAME_CONTEXT);
        ReproductionComparator.Result changed = comparator.compare(
                failure("case-1", "context-1", "run-1", FAILURE_A),
                failure("case-1", "context-1", "run-2", FAILURE_B),
                ReproductionComparator.Scope.SAME_CONTEXT);

        assertEquals(ComparisonOutcome.MATCHED, matched.outcome());
        assertEquals(List.of("TARGET_FAILURE"), changed.changedDimensions());
    }

    @Test
    void comparesBothAssertionFailureDimensionsInStableOrder() {
        RunResultFingerprint reference = both(
                "case-1", "context-1", "run-1", RAW_A, GANTT_A, FAILURE_A);
        RunResultFingerprint current = both(
                "case-1", "context-2", "run-2", RAW_B, GANTT_B, FAILURE_B);

        ReproductionComparator.Result result = comparator.compare(
                reference, current, ReproductionComparator.Scope.CROSS_CONTEXT);

        assertEquals(ComparisonOutcome.CHANGED, result.outcome());
        assertEquals(List.of("GANTT", "TARGET_FAILURE"), result.changedDimensions());
        assertEquals(
                "Baseline CHANGED; scope=CROSS_CONTEXT; referenceRunId=run-1; "
                        + "changedDimensions=GANTT,TARGET_FAILURE",
                result.summary());
    }

    @Test
    void rejectsComparisonAcrossCases() {
        assertThrows(IllegalArgumentException.class, () -> comparator.compare(
                gantt("case-1", "context-1", "run-1", RAW_A, GANTT_A),
                gantt("case-2", "context-1", "run-2", RAW_A, GANTT_A),
                ReproductionComparator.Scope.CROSS_CONTEXT));
    }

    private void assertChanged(
            List<String> dimensions,
            RunResultFingerprint reference,
            RunResultFingerprint current) {
        ReproductionComparator.Result result = comparator.compare(
                reference, current, ReproductionComparator.Scope.SAME_CONTEXT);
        assertEquals(ComparisonOutcome.CHANGED, result.outcome());
        assertEquals(dimensions, result.changedDimensions());
    }

    private static RunResultFingerprint gantt(
            String caseId, String contextId, String runId, String raw, String normalized) {
        return fingerprint(caseId, contextId, runId,
                Optional.of(raw), Optional.of(normalized), Optional.empty());
    }

    private static RunResultFingerprint failure(
            String caseId, String contextId, String runId, String failure) {
        return fingerprint(caseId, contextId, runId,
                Optional.empty(), Optional.empty(), Optional.of(failure));
    }

    private static RunResultFingerprint both(
            String caseId, String contextId, String runId,
            String raw, String normalized, String failure) {
        return fingerprint(caseId, contextId, runId,
                Optional.of(raw), Optional.of(normalized), Optional.of(failure));
    }

    private static RunResultFingerprint fingerprint(
            String caseId,
            String contextId,
            String runId,
            Optional<String> raw,
            Optional<String> normalized,
            Optional<String> failure) {
        return new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT,
                new CaseId(caseId),
                new ContextId(contextId),
                new RunId(runId),
                raw,
                normalized,
                failure);
    }
}
