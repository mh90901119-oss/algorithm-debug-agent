package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.GanttOutcome;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 一次目标测试进程与可选调度结果的组合事实。
 * 进程成功与否不决定 Gantt 是否存在，例如断言失败前仍可能已输出完整调度结果。
 */
public record ScheduleRunResult<T extends ScheduleResultSnapshot>(
        RunResult run,
        GanttOutcome ganttOutcome,
        Optional<CapturedScheduleResult<T>> scheduleResult,
        Optional<AgentFailureDiagnostic> agentFailure,
        Optional<Throwable> agentFailureCause,
        List<Path> changedOutputCandidates) {

    /** 校验 Gantt 状态、捕获结果和 Agent 诊断的组合关系。 */
    public ScheduleRunResult {
        if (run == null || ganttOutcome == null || scheduleResult == null
                || agentFailure == null || agentFailureCause == null
                || changedOutputCandidates == null) {
            throw new IllegalArgumentException("ScheduleRunResult fields must not be null");
        }
        changedOutputCandidates = List.copyOf(changedOutputCandidates);
        if (ganttOutcome == GanttOutcome.PRESENT
                && (scheduleResult.isEmpty() || agentFailure.isPresent()
                || agentFailureCause.isPresent())) {
            throw new IllegalArgumentException("PRESENT must contain a schedule result and must not contain a collection failure");
        }
        if (ganttOutcome == GanttOutcome.ABSENT
                && (scheduleResult.isPresent() || agentFailure.isPresent()
                || agentFailureCause.isPresent())) {
            throw new IllegalArgumentException("ABSENT must not contain a schedule result or collection failure");
        }
        if (ganttOutcome == GanttOutcome.INCOMPLETE
                && (scheduleResult.isPresent() || agentFailure.isEmpty()
                || agentFailureCause.isEmpty())) {
            throw new IllegalArgumentException("INCOMPLETE must contain a collection failure/cause and must not contain a complete schedule result");
        }
    }

    /** 创建未观察到 Gantt 输出的运行结果。 */
    public static <T extends ScheduleResultSnapshot> ScheduleRunResult<T> absent(RunResult run) {
        return new ScheduleRunResult<>(
                run, GanttOutcome.ABSENT, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    /** 创建成功捕获完整 Gantt 的运行结果。 */
    public static <T extends ScheduleResultSnapshot> ScheduleRunResult<T> present(
            RunResult run,
            CapturedScheduleResult<T> scheduleResult,
            List<Path> changedOutputCandidates) {
        return new ScheduleRunResult<>(run, GanttOutcome.PRESENT,
                Optional.of(scheduleResult), Optional.empty(), Optional.empty(), changedOutputCandidates);
    }

    /** 创建 Gantt 有变化但无法完成验证或捕获的运行结果。 */
    public static <T extends ScheduleResultSnapshot> ScheduleRunResult<T> incomplete(
            RunResult run,
            String code,
            Throwable cause,
            List<Path> changedOutputCandidates) {
        if (cause == null) {
            throw new IllegalArgumentException("cause must not be null");
        }
        return new ScheduleRunResult<>(run, GanttOutcome.INCOMPLETE, Optional.empty(),
                Optional.of(new AgentFailureDiagnostic(
                        code, safeMessage(code), cause.getClass().getName())),
                Optional.of(cause),
                changedOutputCandidates);
    }

    /** 创建 Harness 结构化异常对应的不完整 Gantt 结果。 */
    public static <T extends ScheduleResultSnapshot> ScheduleRunResult<T> incomplete(
            RunResult run, HarnessException failure, List<Path> changedOutputCandidates) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return incomplete(run, failure.code(), cause, changedOutputCandidates);
    }

    private static String safeMessage(String code) {
        return "Gantt capture did not complete; see the Agent log. Error code: " + code;
    }
}
