package org.example.algorithmdebug.harness;

import java.util.List;

/**
 * 超时或中断后的进程树终止结果。
 *
 * @param attempted 是否执行过清理
 * @param gracefulSignals 正常终止信号数量
 * @param forcedSignals 强制终止信号数量
 * @param survivingProcessIds 清理预算结束后仍存活的进程 ID
 */
public record TerminationReport(
        boolean attempted,
        int gracefulSignals,
        int forcedSignals,
        List<Long> survivingProcessIds) {

    /** 防御性复制 PID 并校验计数。 */
    public TerminationReport {
        if (gracefulSignals < 0 || forcedSignals < 0) {
            throw new IllegalArgumentException("The termination signal count must not be negative");
        }
        if (survivingProcessIds == null || survivingProcessIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("survivingProcessIds must be a set of valid PIDs");
        }
        survivingProcessIds = List.copyOf(survivingProcessIds);
        if (!attempted && (gracefulSignals != 0 || forcedSignals != 0 || !survivingProcessIds.isEmpty())) {
            throw new IllegalArgumentException("Termination actions or surviving PIDs must not be recorded when cleanup was not performed");
        }
    }

    /** @return 未触发进程清理的报告 */
    public static TerminationReport notAttempted() {
        return new TerminationReport(false, 0, 0, List.of());
    }
}
