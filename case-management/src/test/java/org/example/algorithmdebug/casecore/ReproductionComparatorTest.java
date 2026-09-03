package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReproductionComparatorTest {

    private static final String FAILURE_A = "e".repeat(64);
    private static final String FAILURE_B = "f".repeat(64);

    private final ReproductionComparator comparator = new ReproductionComparator();

    @Test
    void matchesSameTargetFailure() {
        ReproductionComparator.Result result = comparator.compare(
                failure("case-1", "analysis-1", "run-1", FAILURE_A),
                failure("case-1", "analysis-1", "run-2", FAILURE_A));

        assertEquals(ComparisonOutcome.MATCHED, result.outcome());
        assertEquals(List.of(), result.changedDimensions());
        assertEquals(
                "Baseline MATCHED; scope=SAME_ANALYSIS; referenceRunId=run-1; changedDimensions=NONE",
                result.summary());
    }

    @Test
    void reportsTargetFailureChange() {
        ReproductionComparator.Result result = comparator.compare(
                failure("case-1", "analysis-1", "run-1", FAILURE_A),
                failure("case-1", "analysis-1", "run-2", FAILURE_B));

        assertEquals(ComparisonOutcome.CHANGED, result.outcome());
        assertEquals(List.of("TARGET_FAILURE"), result.changedDimensions());
    }

    @Test
    void rejectsComparisonAcrossCases() {
        assertThrows(IllegalArgumentException.class, () -> comparator.compare(
                failure("case-1", "analysis-1", "run-1", FAILURE_A),
                failure("case-2", "analysis-1", "run-2", FAILURE_A)));
        assertThrows(IllegalArgumentException.class, () -> comparator.compare(
                failure("case-1", "analysis-1", "run-1", FAILURE_A),
                failure("case-1", "analysis-2", "run-2", FAILURE_A)));
    }

    private static RunResultFingerprint failure(
            String caseId, String analysisId, String runId, String failure) {
        return new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT,
                new CaseId(caseId),
                new AnalysisId(analysisId),
                new RunId(runId),
                failure);
    }
}
