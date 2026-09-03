package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CaseArchiveContractsTest {
    private static final Instant RECORDED_AT = Instant.parse("2026-08-16T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final TargetTest TARGET_TEST = new TargetTest("a.b.ScheduleTest", "case1");

    @Test
    void describesOneAppendOnlyCaseAnalysisWithoutPersistedModelAnswer() {
        CaseManifest manifest = new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                "maven-junit", "Why is the schedule delayed?", RECORDED_AT);
        AnalysisRequest analysis = new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, ANALYSIS_ID,
                "Continue with current runtime evidence", RECORDED_AT.plusSeconds(1));

        assertEquals("maven-junit", manifest.adapterId());
        assertEquals(CASE_ID, analysis.caseId());
        assertFalse(hasRecordComponent(CaseDigest.class, "recentAnalysisResults"));
        assertFalse(hasRecordComponent(CaseDigest.class, "completedAnalysisCount"));
    }

    @Test
    void rejectsUnsupportedCaseVersions() {
        assertThrows(IllegalArgumentException.class, () -> new CaseManifest(
                "1.0", CASE_ID, PROJECT_ID, TARGET_TEST,
                "maven-junit", "Question", RECORDED_AT));
    }

    @Test
    void defensivelyCopiesDigestListsAndDerivesLatestRunOutsideOutcome() {
        List<RunOutcomeSummary> runs = new ArrayList<>();
        runs.add(runOutcome());
        CaseDigest digest = new CaseDigest(
                SchemaVersions.CASE_DIGEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                Optional.of(ANALYSIS_ID), "Continue analysis",
                Optional.of(new RunId("run-1")), runs,
                List.of(), List.of(), List.of(), List.of(),
                1, 1, 0, 0, false);
        runs.clear();

        assertEquals(1, digest.recentRuns().size());
        assertEquals("run-1", digest.latestRunId().orElseThrow().value());
        assertThrows(UnsupportedOperationException.class, () -> digest.recentRuns().clear());
    }

    @Test
    void representsCaseWhoseFirstAnalysisWasNotCommitted() {
        CaseDigest digest = new CaseDigest(
                SchemaVersions.CASE_DIGEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                Optional.empty(), "Initial question", Optional.empty(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                0, 0, 0, 0, false);
        assertTrue(digest.latestAnalysisId().isEmpty());
    }

    private static RunOutcomeSummary runOutcome() {
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                CASE_ID, ANALYSIS_ID, new RunId("run-1"),
                ProcessOutcome.SUCCEEDED, TestOutcome.PASSED, GanttOutcome.ABSENT,
                Optional.empty(), Optional.empty(), ComparisonOutcome.NOT_COMPARED,
                "No baseline comparison", List.of());
    }

    private static boolean hasRecordComponent(Class<?> type, String name) {
        return List.of(type.getRecordComponents()).stream()
                .anyMatch(component -> component.getName().equals(name));
    }
}
