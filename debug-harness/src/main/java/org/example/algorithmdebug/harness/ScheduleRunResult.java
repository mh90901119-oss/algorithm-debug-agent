package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;

import java.util.Optional;

/**
 * 一次目标测试进程与可选调度结果捕获的组合结果。
 *
 * @param run 进程运行事实
 * @param scheduleResult 仅成功运行具有捕获结果
 */
public record ScheduleRunResult<T extends ScheduleResultSnapshot>(
        RunResult run,
        Optional<CapturedScheduleResult<T>> scheduleResult) {

    /** 强制成功必须有结果、失败和超时不得带结果。 */
    public ScheduleRunResult {
        if (run == null || scheduleResult == null) {
            throw new IllegalArgumentException("run 与 scheduleResult 不能为空");
        }
        boolean hasResult = scheduleResult.isPresent();
        if ((run.completion() == RunCompletion.SUCCEEDED) != hasResult) {
            throw new IllegalArgumentException("只有成功运行必须且只能具有捕获调度结果");
        }
    }
}
