package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunOutcomeSummaryTest {

    private static final ArtifactReference GANTT = new ArtifactReference(
            "artifact-gantt", "GANTT", "gantt/result.json", "application/json",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 128);

    @Test
    void shouldKeepTargetFailureAndGanttIndependent() {
        RunOutcomeSummary summary = new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"), true,
                ProcessOutcome.FAILED, TestOutcome.ERROR, GanttOutcome.PRESENT,
                Optional.of(new TargetFailureDiagnostic(
                        FailureCategory.TEST_ERROR, "java.lang.NullPointerException",
                        "value was null", "root cause", "a.b.Algorithm.solve(Algorithm.java:42)")),
                Optional.empty(), ComparisonOutcome.NOT_COMPARED,
                "No baseline comparison requested", List.of(GANTT));

        assertEquals(FailureCategory.TEST_ERROR, summary.targetFailure().orElseThrow().category());
        assertEquals(GanttOutcome.PRESENT, summary.ganttOutcome());
        assertEquals(List.of(GANTT), summary.artifacts());
    }

    @Test
    void shouldKeepAgentFailureSeparateAndDefensivelyCopyArtifacts() {
        List<ArtifactReference> artifacts = new ArrayList<>(List.of(GANTT));
        RunOutcomeSummary summary = new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"), false,
                ProcessOutcome.SUCCEEDED, TestOutcome.PASSED, GanttOutcome.PRESENT,
                Optional.empty(), Optional.of(new AgentFailureDiagnostic("ARTIFACT_WRITE_FAILED", "write failed")),
                ComparisonOutcome.MATCHED, "Semantic hash matched", artifacts);
        artifacts.clear();

        assertTrue(summary.targetFailure().isEmpty());
        assertEquals("ARTIFACT_WRITE_FAILED", summary.agentFailure().orElseThrow().code());
        assertEquals(List.of(GANTT), summary.artifacts());
        assertThrows(UnsupportedOperationException.class, () -> summary.artifacts().clear());
    }
}
