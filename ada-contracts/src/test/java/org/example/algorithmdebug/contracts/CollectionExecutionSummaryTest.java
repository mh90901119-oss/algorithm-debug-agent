package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CollectionExecutionSummaryTest {
    @Test
    void evidenceUsableRequiresMatchedButMatchedCanStillBeUnusable() {
        assertDoesNotThrow(() -> summary(ComparisonOutcome.MATCHED, false));
        assertThrows(IllegalArgumentException.class,
                () -> summary(ComparisonOutcome.CHANGED, true));
    }

    @Test
    void matchedBaselineCanStillBeUnusableWhenSourceChangesAfterCollection() {
        assertDoesNotThrow(() -> new CollectionBaselineCheck(
                "1.0", new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new CollectionId("collection-1"), ComparisonOutcome.MATCHED,
                Optional.of(new RunId("baseline-run")), Optional.of("a".repeat(64)),
                false, "source changed after collection", Instant.EPOCH));
    }

    @Test
    void exposesBoundedArtifactIdsForModelReads() {
        CollectionExecutionSummary summary = summary(ComparisonOutcome.MATCHED, true);

        assertEquals(List.of("summary-1"), summary.artifactIds());
    }

    private static CollectionExecutionSummary summary(
            ComparisonOutcome outcome, boolean usable) {
        return new CollectionExecutionSummary(
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "SUCCESS", outcome, usable, List.of("manifest.json"), List.of("summary-1"));
    }
}
