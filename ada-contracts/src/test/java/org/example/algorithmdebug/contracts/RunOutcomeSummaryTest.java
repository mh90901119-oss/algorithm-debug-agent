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
                new AnalysisId("analysis-1"), new RunId("run-1"),
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
                new AnalysisId("analysis-1"), new RunId("run-1"),
                ProcessOutcome.SUCCEEDED, TestOutcome.PASSED, GanttOutcome.PRESENT,
                Optional.empty(), Optional.of(new AgentFailureDiagnostic("ARTIFACT_WRITE_FAILED", "write failed")),
                ComparisonOutcome.MATCHED, "Semantic hash matched", artifacts);
        artifacts.clear();

        assertTrue(summary.targetFailure().isEmpty());
        assertEquals("ARTIFACT_WRITE_FAILED", summary.agentFailure().orElseThrow().code());
        assertEquals(List.of(GANTT), summary.artifacts());
        assertThrows(UnsupportedOperationException.class, () -> summary.artifacts().clear());
    }

    @Test
    void shouldRejectTargetFailureThatContradictsTestOutcome() {
        TargetFailureDiagnostic testFailure = new TargetFailureDiagnostic(
                FailureCategory.TEST_FAILURE, "org.opentest4j.AssertionFailedError",
                "assertion failed", "", "a.b.TargetTest.runs(TargetTest.java:18)");

        assertThrows(IllegalArgumentException.class, () -> summary(
                TestOutcome.PASSED, GanttOutcome.PRESENT, Optional.of(testFailure), List.of(GANTT)));
        assertThrows(IllegalArgumentException.class, () -> summary(
                TestOutcome.ERROR, GanttOutcome.ABSENT, Optional.of(testFailure), List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(
                TestOutcome.ERROR, GanttOutcome.ABSENT, Optional.empty(), List.of()));
    }

    @Test
    void shouldRequireGanttArtifactWhenGanttIsPresent() {
        assertThrows(IllegalArgumentException.class, () -> summary(
                TestOutcome.PASSED, GanttOutcome.PRESENT, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(
                TestOutcome.PASSED, GanttOutcome.ABSENT, Optional.empty(), List.of(GANTT)));
    }

    @Test
    void shouldRequireDiagnosticWhenTestWasNotExecuted() {
        assertThrows(IllegalArgumentException.class, () -> summary(
                TestOutcome.NOT_EXECUTED, GanttOutcome.ABSENT, Optional.empty(), List.of()));

        RunOutcomeSummary agentFailure = new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                ProcessOutcome.NOT_STARTED, TestOutcome.NOT_EXECUTED, GanttOutcome.ABSENT,
                Optional.empty(), Optional.of(new AgentFailureDiagnostic(
                        "AGENT_PROCESS_START_FAILED", "Maven process could not be started")),
                ComparisonOutcome.NOT_COMPARED, "No comparison", List.of());

        assertEquals("AGENT_PROCESS_START_FAILED", agentFailure.agentFailure().orElseThrow().code());
    }

    @Test
    void shouldBoundAgentFailureMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentFailureDiagnostic("AGENT_FAILED", "x".repeat(8193)));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentFailureDiagnostic("x".repeat(257), "failed"));
    }

    private RunOutcomeSummary summary(
            TestOutcome testOutcome,
            GanttOutcome ganttOutcome,
            Optional<TargetFailureDiagnostic> targetFailure,
            List<ArtifactReference> artifacts) {
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                ProcessOutcome.FAILED, testOutcome, ganttOutcome,
                targetFailure, Optional.empty(), ComparisonOutcome.NOT_COMPARED,
                "No comparison", artifacts);
    }
}
