package org.example.algorithmdebug.contracts;

/**
 * 将运行前 Case 身份与运行后的调度语义结果绑定。
 *
 * @param caseFingerprint 运行前冻结的代码、输入、UT 与环境身份
 * @param scheduleSemanticHash 去除非业务噪声后的调度结果 SHA-256
 */
public record ExecutionIdentity(
        CaseFingerprint caseFingerprint,
        String scheduleSemanticHash) {

    /** 校验一次基线运行所需的身份字段均已冻结。 */
    public ExecutionIdentity {
        caseFingerprint = ContractChecks.requireNonNull(caseFingerprint, "caseFingerprint");
        scheduleSemanticHash = ContractChecks.requireSha256(
                scheduleSemanticHash, "scheduleSemanticHash");
    }
}
