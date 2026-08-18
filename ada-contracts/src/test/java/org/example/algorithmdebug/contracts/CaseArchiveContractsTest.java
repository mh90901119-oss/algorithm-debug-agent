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
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final TargetTest TARGET_TEST = new TargetTest("a.b.ScheduleTest", "case1");

    @Test
    void shouldDescribeOneAppendOnlyCaseAnalysisWithMinimalContext() {
        CaseManifest manifest = new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                "wafer-demo", "为什么当前调度结果存在空闲？", RECORDED_AT);
        ContextRecord context = new ContextRecord(
                SchemaVersions.CONTEXT_RECORD, CASE_ID, CONTEXT_ID, RECORDED_AT);
        AnalysisRequest analysis = new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                "继续分析设备空闲区间", RECORDED_AT.plusSeconds(1));

        assertEquals("wafer-demo", manifest.adapterId());
        assertEquals(CASE_ID, context.caseId());
        assertEquals("继续分析设备空闲区间", analysis.question());
    }

    @Test
    void shouldRejectUnsupportedContextAndCaseVersions() {
        assertThrows(IllegalArgumentException.class, () -> new ContextRecord(
                "1.0", CASE_ID, CONTEXT_ID, RECORDED_AT));
        assertThrows(IllegalArgumentException.class, () -> new CaseManifest(
                "1.0", CASE_ID, PROJECT_ID, TARGET_TEST,
                "wafer-demo", "问题", RECORDED_AT));
        assertThrows(IllegalArgumentException.class, () -> new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                " ", "问题", RECORDED_AT));
    }

    @Test
    void shouldDefensivelyCopyDigestListsAndDeriveLatestRunOutsideOutcome() {
        List<RunOutcomeSummary> runs = new ArrayList<>();
        runs.add(runOutcome());
        CaseDigest digest = new CaseDigest(
                SchemaVersions.CASE_DIGEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                Optional.of(CONTEXT_ID), Optional.of(ANALYSIS_ID), "继续分析设备空闲区间",
                Optional.of(new RunId("run-1")), runs, List.of(), List.of(), List.of(),
                List.of(), List.of(), 1, 1, 1, 0, 0, 0, false);
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
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                0, 0, 0, 0, 0, 0, false);

        assertTrue(digest.latestContextId().isEmpty());
        assertTrue(digest.latestAnalysisId().isEmpty());
    }

    @Test
    void shouldBoundQuestionsAndAdapterIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                "x".repeat(65_537), RECORDED_AT));
        assertThrows(IllegalArgumentException.class, () -> new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET_TEST,
                "x".repeat(513), "问题", RECORDED_AT));
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
