package org.example.algorithmdebug.contracts;

/**
 * Baseline 稳定性判断使用的一次不可变运行观察。
 *
 * @param runId 运行 ID
 * @param scheduleSemanticHash 本次调度语义 SHA-256
 */
public record BaselineRunObservation(RunId runId, String scheduleSemanticHash) {

    /** 校验运行引用和语义哈希。 */
    public BaselineRunObservation {
        runId = ContractChecks.requireNonNull(runId, "runId");
        scheduleSemanticHash = ContractChecks.requireSha256(
                scheduleSemanticHash, "scheduleSemanticHash");
    }
}
