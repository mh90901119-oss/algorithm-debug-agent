package org.example.algorithmdebug.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceSnapshot;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.junit.jupiter.api.Test;

class CollectionApplicationServicePolicyTest {

    @Test
    void rejectsIncompleteSourceSnapshotEvenWhenObservedFingerprintMatches() {
        SourceSnapshot incomplete = new SourceSnapshot(
                "a".repeat(64), 1, 16, SnapshotCompleteness.INCOMPLETE);

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                CollectionApplicationService.requireSourceReady(
                        incomplete, incomplete, incomplete.sha256()));

        org.junit.jupiter.api.Assertions.assertEquals(
                "CONTEXT_SOURCE_SNAPSHOT_INCOMPLETE", failure.code());
    }

    @Test
    void blocksTruncatedOrEmptyTraceFromConfirmationEvenWhenBaselineMatches() {
        CollectionBaselineCheck matched = matchedBaseline();

        assertFalse(CollectionApplicationService.isEvidenceUsable(
                CollectionCompletion.TRUNCATED, 10, matched));
        assertFalse(CollectionApplicationService.isEvidenceUsable(
                CollectionCompletion.SUCCESS, 0, matched));
        assertTrue(CollectionApplicationService.isEvidenceUsable(
                CollectionCompletion.SUCCESS, 10, matched));
    }

    private static CollectionBaselineCheck matchedBaseline() {
        return new CollectionBaselineCheck(
                "1.0", new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new CollectionId("collection-1"), ComparisonOutcome.MATCHED,
                Optional.of(new RunId("baseline-run")), Optional.of("b".repeat(64)),
                true, "matched", Instant.EPOCH);
    }
}
