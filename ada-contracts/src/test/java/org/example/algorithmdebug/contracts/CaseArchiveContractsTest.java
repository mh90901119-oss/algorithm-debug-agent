package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseArchiveContractsTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-08-16T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final TargetTest TARGET_TEST = new TargetTest("a.b.ScheduleTest", "case1");

    @Test
    void shouldDescribeOneAppendOnlyCaseAnalysis() {
        CaseManifest manifest = new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                "为什么当前调度结果存在空闲？", RECORDED_AT);
        ContextSnapshot context = completeContext();
        AnalysisRequest analysis = new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                "继续分析设备空闲区间", RECORDED_AT.plusSeconds(1));
        RunRequest run = new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                new RunId("run-1"), TARGET_TEST, "UNINSTRUMENTED", RECORDED_AT.plusSeconds(2));

        assertEquals(PROJECT_ID, manifest.projectId());
        assertEquals(SnapshotCompleteness.COMPLETE, context.completeness());
        assertEquals("继续分析设备空闲区间", analysis.question());
        assertEquals("UNINSTRUMENTED", run.executionMode());
    }

    @Test
    void shouldRejectInvalidSnapshotFacts() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceSnapshot("not-a-hash", 1, 10, SnapshotCompleteness.COMPLETE));
        assertThrows(IllegalArgumentException.class,
                () -> new InputSnapshot(InputSnapshotStatus.PRESENT, "input/case.json", "", 10, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new InputSnapshot(InputSnapshotStatus.MISSING, "", "a".repeat(64), 0, "missing"));
        assertThrows(IllegalArgumentException.class, () -> new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, CONTEXT_ID, PROJECT_ID, TARGET_TEST,
                "UNAVAILABLE", source(), input(), build(), SnapshotCompleteness.COMPLETE,
                "not-a-hash", List.of(), RECORDED_AT));
    }

    @Test
    void shouldDefensivelyCopyDigestListsAndDeriveLatestRunOutsideOutcome() {
        List<RunOutcomeSummary> runs = new ArrayList<>();
        runs.add(runOutcome());
        CaseDigest digest = new CaseDigest(
                SchemaVersions.CASE_DIGEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                Optional.of(CONTEXT_ID), Optional.of(ANALYSIS_ID), "继续分析设备空闲区间",
                Optional.of(new RunId("run-1")), runs, List.of(), List.of(),
                1, 1, 1, false);
        runs.clear();

        assertEquals(1, digest.recentRuns().size());
        assertEquals("run-1", digest.latestRunId().orElseThrow().value());
        assertThrows(UnsupportedOperationException.class, () -> digest.recentRuns().clear());
        assertFalse(hasRecordComponent(RunOutcomeSummary.class, "latestRunForAnalysis"));
    }

    @Test
    void shouldRepresentCaseWhoseFirstContextWasNotCommitted() {
        CaseDigest digest = new CaseDigest(
                SchemaVersions.CASE_DIGEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                Optional.empty(), Optional.empty(), "为什么有空闲？", Optional.empty(),
                List.of(), List.of(), List.of(), 0, 0, 0, false);

        assertTrue(digest.latestContextId().isEmpty());
        assertTrue(digest.latestAnalysisId().isEmpty());
    }

    @Test
    void shouldBoundQuestionsAndWarnings() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                "x".repeat(65_537), RECORDED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new ArchiveWarning("ARCHIVE_INVALID", "x".repeat(2_049), "runs/run-1/run-outcome.json"));
        assertThrows(IllegalArgumentException.class, () -> new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, CONTEXT_ID, PROJECT_ID, TARGET_TEST,
                "UNAVAILABLE", source(), input(), build(), SnapshotCompleteness.INCOMPLETE,
                "d".repeat(64), java.util.Collections.nCopies(21, "warning"), RECORDED_AT));
    }

    @Test
    void unresolvedInputMustMakeContextIncomplete() {
        InputSnapshot unresolved = new InputSnapshot(
                InputSnapshotStatus.UNRESOLVED, "", "", 0, "adapter failed");

        assertThrows(IllegalArgumentException.class, () -> new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, CONTEXT_ID, PROJECT_ID, TARGET_TEST,
                "UNAVAILABLE", source(), unresolved, build(), SnapshotCompleteness.COMPLETE,
                "d".repeat(64), List.of(), RECORDED_AT));
    }

    private static ContextSnapshot completeContext() {
        return new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, CONTEXT_ID, PROJECT_ID, TARGET_TEST,
                "abc123", source(), input(), build(), SnapshotCompleteness.COMPLETE,
                "d".repeat(64), List.of(), RECORDED_AT);
    }

    private static SourceSnapshot source() {
        return new SourceSnapshot("a".repeat(64), 2, 128, SnapshotCompleteness.COMPLETE);
    }

    private static InputSnapshot input() {
        return new InputSnapshot(
                InputSnapshotStatus.PRESENT, "input/case.json", "b".repeat(64), 64, "");
    }

    private static BuildSnapshot build() {
        return new BuildSnapshot("c".repeat(64), "21.0.4", "wafer-demo", "0.2.0");
    }

    private static RunOutcomeSummary runOutcome() {
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                CASE_ID, CONTEXT_ID, ANALYSIS_ID, new RunId("run-1"),
                ProcessOutcome.SUCCEEDED, TestOutcome.PASSED, GanttOutcome.ABSENT,
                Optional.empty(), Optional.empty(), ComparisonOutcome.NOT_COMPARED,
                "Baseline comparison is not implemented in this slice", List.of());
    }

    private static boolean hasRecordComponent(Class<?> type, String name) {
        return List.of(type.getRecordComponents()).stream()
                .anyMatch(component -> component.getName().equals(name));
    }
}
