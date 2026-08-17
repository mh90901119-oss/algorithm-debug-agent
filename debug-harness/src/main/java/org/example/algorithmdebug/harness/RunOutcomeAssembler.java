package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
import org.example.algorithmdebug.contracts.TestOutcome;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 将进程、Surefire、Gantt 与 Agent 事实纯函数式映射为一次 Run 摘要。 */
public final class RunOutcomeAssembler {

    private static final int MAX_MARKER_TEXT = 65_536;
    private static final int MAX_COMPARISON_SUMMARY = 2_048;

    /**
     * 组装六个正交结果维度；该方法不读文件、不写归档、不推断算法业务根因。
     *
     * @param request 已持久化的 Run 请求
     * @param run 已启动进程的结果；启动失败时为空
     * @param surefireResult 仅来自本次变化报告的目标测试事实
     * @param ganttOutcome Gantt 独立结果
     * @param agentFailure Agent/Harness 独立失败
     * @param boundedMavenOutput 有界 Maven 输出，仅用于粗粒度阶段标记
     * @param artifacts 已归档的不可变产物引用
     * @param comparisonOutcome 调用方确定的参考比较结果
     * @param comparisonSummary 有界的确定性比较摘要
     */
    public RunOutcomeSummary assemble(
            RunRequest request,
            Optional<RunResult> run,
            Optional<SurefireTestResult> surefireResult,
            GanttOutcome ganttOutcome,
            Optional<AgentFailureDiagnostic> agentFailure,
            String boundedMavenOutput,
            List<ArtifactReference> artifacts,
            ComparisonOutcome comparisonOutcome,
            String comparisonSummary) {
        if (request == null || run == null || surefireResult == null || ganttOutcome == null
                || agentFailure == null || boundedMavenOutput == null || artifacts == null
                || comparisonOutcome == null || comparisonSummary == null) {
            throw new IllegalArgumentException("RunOutcomeAssembler 参数不能为空");
        }
        if (boundedMavenOutput.length() > MAX_MARKER_TEXT) {
            throw new IllegalArgumentException("Maven 阶段标记文本超过 64 KiB 预算");
        }
        if (run.isEmpty() && surefireResult.isPresent()) {
            throw new IllegalArgumentException("进程未启动时不能包含本次 Surefire 事实");
        }
        if (run.isEmpty() && agentFailure.isEmpty()) {
            throw new IllegalArgumentException("进程未启动时必须包含 Agent 诊断");
        }
        comparisonSummary = comparisonSummary.strip();
        if (comparisonSummary.isEmpty()
                || comparisonSummary.length() > MAX_COMPARISON_SUMMARY) {
            throw new IllegalArgumentException("比较摘要必须为非空且不超过 2 KiB");
        }

        ProcessOutcome processOutcome = processOutcome(run);
        TestFacts testFacts = testFacts(run, surefireResult, boundedMavenOutput);
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY,
                "TARGET_TEST_RUN_COMPLETED",
                request.caseId(), request.contextId(), request.analysisId(), request.runId(),
                processOutcome, testFacts.outcome(), ganttOutcome,
                testFacts.failure(), agentFailure,
                comparisonOutcome,
                comparisonSummary,
                artifacts);
    }

    private static ProcessOutcome processOutcome(Optional<RunResult> run) {
        if (run.isEmpty()) {
            return ProcessOutcome.NOT_STARTED;
        }
        return switch (run.orElseThrow().completion()) {
            case SUCCEEDED -> ProcessOutcome.SUCCEEDED;
            case FAILED -> ProcessOutcome.FAILED;
            case TIMED_OUT -> ProcessOutcome.TIMED_OUT;
        };
    }

    private static TestFacts testFacts(
            Optional<RunResult> run,
            Optional<SurefireTestResult> surefireResult,
            String output) {
        if (surefireResult.isPresent()) {
            SurefireTestResult result = surefireResult.orElseThrow();
            return new TestFacts(result.outcome(), result.targetFailure());
        }
        if (run.isEmpty()) {
            return new TestFacts(TestOutcome.NOT_EXECUTED, Optional.empty());
        }
        String normalized = output.toLowerCase(Locale.ROOT);
        if (isCompileFailure(normalized)) {
            return notExecuted(FailureCategory.BUILD_FAILURE,
                    "Maven compilation failed before the target test completed");
        }
        if (isNoMatchingTest(normalized)) {
            return notExecuted(FailureCategory.TEST_NOT_EXECUTED,
                    "Maven/Surefire did not execute the selected target test");
        }
        return new TestFacts(TestOutcome.UNKNOWN, Optional.empty());
    }

    private static TestFacts notExecuted(FailureCategory category, String message) {
        return new TestFacts(TestOutcome.NOT_EXECUTED, Optional.of(
                new TargetFailureDiagnostic(category, "", message, "", "")));
    }

    private static boolean isCompileFailure(String value) {
        return value.contains("[error] compilation error")
                || value.contains("compilation failure")
                || value.contains("there are test compilation errors")
                || value.contains("failed to execute goal")
                && value.contains("maven-compiler-plugin");
    }

    private static boolean isNoMatchingTest(String value) {
        return value.contains("no tests matching pattern")
                || value.contains("no tests were executed")
                || value.contains("no tests found");
    }

    private record TestFacts(
            TestOutcome outcome,
            Optional<TargetFailureDiagnostic> failure) {
    }
}
