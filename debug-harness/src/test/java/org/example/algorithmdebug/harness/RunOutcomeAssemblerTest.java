package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunOutcomeAssemblerTest {

    private static final Instant TIME = Instant.parse("2026-08-16T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private final RunOutcomeAssembler assembler = new RunOutcomeAssembler();

    @Test
    void passedSurefireFactAndGanttAreIndependentOfProcessMapping() {
        SurefireTestResult test = new SurefireTestResult(
                TestOutcome.PASSED, Optional.empty(), Path.of("TEST-a.b.TargetTest.xml"));

        RunOutcomeSummary outcome = assemble(
                Optional.of(run(RunCompletion.SUCCEEDED)), Optional.of(test),
                GanttOutcome.PRESENT, Optional.empty(), "", List.of(gantt()));

        assertEquals(ProcessOutcome.SUCCEEDED, outcome.processOutcome());
        assertEquals(TestOutcome.PASSED, outcome.testOutcome());
        assertEquals(GanttOutcome.PRESENT, outcome.ganttOutcome());
        assertEquals(ComparisonOutcome.NOT_COMPARED, outcome.comparisonOutcome());
    }

    @Test
    void targetExceptionDoesNotBecomeAgentFailure() {
        TargetFailureDiagnostic failure = new TargetFailureDiagnostic(
                FailureCategory.TEST_ERROR, "java.lang.NullPointerException",
                "value was null", "", "a.b.Algorithm.solve(Algorithm.java:42)");
        SurefireTestResult test = new SurefireTestResult(
                TestOutcome.ERROR, Optional.of(failure), Path.of("TEST-a.b.TargetTest.xml"));

        RunOutcomeSummary outcome = assemble(
                Optional.of(run(RunCompletion.FAILED)), Optional.of(test),
                GanttOutcome.ABSENT, Optional.empty(), "", List.of());

        assertEquals(ProcessOutcome.FAILED, outcome.processOutcome());
        assertEquals(TestOutcome.ERROR, outcome.testOutcome());
        assertEquals(FailureCategory.TEST_ERROR,
                outcome.targetFailure().orElseThrow().category());
        assertTrue(outcome.agentFailure().isEmpty());
    }

    @Test
    void staleReportCannotTurnCompileFailureIntoOldTestFailure() {
        RunOutcomeSummary outcome = assemble(
                Optional.of(run(RunCompletion.FAILED)), Optional.empty(),
                GanttOutcome.ABSENT, Optional.empty(),
                "[ERROR] COMPILATION ERROR\nFailed to execute goal maven-compiler-plugin", List.of());

        assertEquals(TestOutcome.NOT_EXECUTED, outcome.testOutcome());
        assertEquals(FailureCategory.BUILD_FAILURE,
                outcome.targetFailure().orElseThrow().category());
    }

    @Test
    void noMatchingTestMarkerIsReportedWithoutGuessingAnException() {
        RunOutcomeSummary outcome = assemble(
                Optional.of(run(RunCompletion.FAILED)), Optional.empty(),
                GanttOutcome.ABSENT, Optional.empty(),
                "No tests matching pattern a.b.TargetTest#runs were executed", List.of());

        assertEquals(TestOutcome.NOT_EXECUTED, outcome.testOutcome());
        assertEquals(FailureCategory.TEST_NOT_EXECUTED,
                outcome.targetFailure().orElseThrow().category());
        assertEquals("", outcome.targetFailure().orElseThrow().exceptionClass());
    }

    @Test
    void successfulCompilerPluginLogWithoutCurrentReportRemainsUnknown() {
        RunOutcomeSummary outcome = assemble(
                Optional.of(run(RunCompletion.SUCCEEDED)), Optional.empty(),
                GanttOutcome.ABSENT, Optional.empty(),
                "[INFO] maven-compiler-plugin compile completed successfully", List.of());

        assertEquals(TestOutcome.UNKNOWN, outcome.testOutcome());
        assertTrue(outcome.targetFailure().isEmpty());
    }

    @Test
    void timeoutKeepsUnknownTestFactWhenNoCurrentReportExists() {
        RunOutcomeSummary outcome = assemble(
                Optional.of(run(RunCompletion.TIMED_OUT)), Optional.empty(),
                GanttOutcome.ABSENT, Optional.empty(), "", List.of());

        assertEquals(ProcessOutcome.TIMED_OUT, outcome.processOutcome());
        assertEquals(TestOutcome.UNKNOWN, outcome.testOutcome());
        assertTrue(outcome.targetFailure().isEmpty());
    }

    @Test
    void processStartFailureIsAgentFailureAndTestWasNotExecuted() {
        AgentFailureDiagnostic failure = new AgentFailureDiagnostic(
                "HARNESS_PROCESS_START_FAILED", "无法启动 Maven", "java.io.IOException");

        RunOutcomeSummary outcome = assemble(
                Optional.empty(), Optional.empty(), GanttOutcome.ABSENT,
                Optional.of(failure), "", List.of());

        assertEquals(ProcessOutcome.NOT_STARTED, outcome.processOutcome());
        assertEquals(TestOutcome.NOT_EXECUTED, outcome.testOutcome());
        assertEquals("HARNESS_PROCESS_START_FAILED",
                outcome.agentFailure().orElseThrow().code());
    }

    @Test
    void ganttPostProcessingFailureDoesNotErasePassingTestFact() {
        SurefireTestResult test = new SurefireTestResult(
                TestOutcome.PASSED, Optional.empty(), Path.of("TEST-a.b.TargetTest.xml"));
        AgentFailureDiagnostic failure = new AgentFailureDiagnostic(
                "HARNESS_GANTT_PROCESSING_FAILED", "Gantt 解析失败", "java.lang.IllegalStateException");

        RunOutcomeSummary outcome = assemble(
                Optional.of(run(RunCompletion.SUCCEEDED)), Optional.of(test),
                GanttOutcome.INCOMPLETE, Optional.of(failure), "", List.of());

        assertEquals(ProcessOutcome.SUCCEEDED, outcome.processOutcome());
        assertEquals(TestOutcome.PASSED, outcome.testOutcome());
        assertEquals(GanttOutcome.INCOMPLETE, outcome.ganttOutcome());
        assertEquals("HARNESS_GANTT_PROCESSING_FAILED",
                outcome.agentFailure().orElseThrow().code());
    }

    @Test
    void keepsExplicitComparisonDecisionFromCaller() {
        SurefireTestResult test = new SurefireTestResult(
                TestOutcome.PASSED, Optional.empty(), Path.of("TEST-a.b.TargetTest.xml"));

        RunOutcomeSummary outcome = assembler.assemble(
                request(), Optional.of(run(RunCompletion.SUCCEEDED)), Optional.of(test),
                GanttOutcome.PRESENT, Optional.empty(), "", List.of(gantt()),
                ComparisonOutcome.MATCHED,
                "Baseline MATCHED; scope=SAME_CONTEXT; referenceRunId=run-0; "
                        + "changedDimensions=NONE");

        assertEquals(ComparisonOutcome.MATCHED, outcome.comparisonOutcome());
        assertEquals(
                "Baseline MATCHED; scope=SAME_CONTEXT; referenceRunId=run-0; "
                        + "changedDimensions=NONE",
                outcome.comparisonSummary());
    }

    @Test
    void rejectsBlankOrOversizedComparisonSummary() {
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(
                request(), Optional.of(run(RunCompletion.SUCCEEDED)), Optional.empty(),
                GanttOutcome.ABSENT, Optional.empty(), "", List.of(),
                ComparisonOutcome.NOT_COMPARED, " "));
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(
                request(), Optional.of(run(RunCompletion.SUCCEEDED)), Optional.empty(),
                GanttOutcome.ABSENT, Optional.empty(), "", List.of(),
                ComparisonOutcome.NOT_COMPARED, "x".repeat(2_049)));
    }

    private RunOutcomeSummary assemble(
            Optional<RunResult> run,
            Optional<SurefireTestResult> test,
            GanttOutcome ganttOutcome,
            Optional<AgentFailureDiagnostic> agentFailure,
            String boundedMavenOutput,
            List<ArtifactReference> artifacts) {
        return assembler.assemble(
                request(), run, test, ganttOutcome, agentFailure, boundedMavenOutput, artifacts,
                ComparisonOutcome.NOT_COMPARED, "No valid reproduction reference");
    }

    private RunRequest request() {
        return new RunRequest(
                SchemaVersions.RUN_REQUEST, new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new TargetTest("a.b.TargetTest", "runs"), "UNINSTRUMENTED", TIME);
    }

    private RunResult run(RunCompletion completion) {
        OptionalInt exit = switch (completion) {
            case SUCCEEDED -> OptionalInt.of(0);
            case FAILED -> OptionalInt.of(1);
            case TIMED_OUT -> OptionalInt.empty();
        };
        TerminationReport termination = completion == RunCompletion.TIMED_OUT
                ? new TerminationReport(true, 1, 1, List.of())
                : TerminationReport.notAttempted();
        return new RunResult(
                completion, exit, TIME, TIME.plusSeconds(1), Duration.ofSeconds(1), 42,
                new RunLog(temporaryDirectory.resolve("stdout.log"), 0, 0, false),
                new RunLog(temporaryDirectory.resolve("stderr.log"), 0, 0, false), termination);
    }

    private static ArtifactReference gantt() {
        return new ArtifactReference(
                "artifact-gantt", "GANTT", "raw/gantt.json", "application/json",
                "a".repeat(64), 1);
    }
}
