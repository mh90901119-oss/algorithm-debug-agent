package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;

import java.util.Optional;

/**
 * 一次目标测试进程与可选调度结果的组合事实。
 * 进程成功与否不决定 Gantt 是否存在，例如断言失败前仍可能已输出完整调度结果。
 */
public record ScheduleRunResult<T extends ScheduleResultSnapshot>(
        RunResult run,
        Optional<CapturedScheduleResult<T>> scheduleResult) {

    /** 进程结果与调度产物相互独立；仅校验二者容器本身非空。 */
    public ScheduleRunResult {
        if (run == null || scheduleResult == null) {
            throw new IllegalArgumentException("run 与 scheduleResult 不能为空");
        }
    }
}
