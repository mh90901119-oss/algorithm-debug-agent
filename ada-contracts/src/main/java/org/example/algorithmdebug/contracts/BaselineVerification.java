package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * 同一运行前身份下多次 Baseline 的稳定性状态。
 *
 * @param schemaVersion Schema 版本
 * @param caseFingerprint 运行前 Case 身份
 * @param canonicalSemanticHash 首次成功结果的 canonical 语义哈希
 * @param requiredMatchingRuns 判定稳定所需连续匹配次数
 * @param observations 按发生顺序保存的运行观察
 * @param state 当前 Case Baseline 状态
 */
public record BaselineVerification(
        String schemaVersion,
        CaseFingerprint caseFingerprint,
        String canonicalSemanticHash,
        int requiredMatchingRuns,
        List<BaselineRunObservation> observations,
        CaseLifecycleState state) {

    /** 校验验证记录自洽，并防御性复制观察列表。 */
    public BaselineVerification {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.BASELINE_VERIFICATION.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 BaselineVerification schemaVersion: " + schemaVersion);
        }
        caseFingerprint = ContractChecks.requireNonNull(caseFingerprint, "caseFingerprint");
        canonicalSemanticHash = ContractChecks.requireSha256(
                canonicalSemanticHash, "canonicalSemanticHash");
        if (requiredMatchingRuns < 2) {
            throw new IllegalArgumentException("requiredMatchingRuns 不能小于 2");
        }
        observations = ContractChecks.immutableList(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("observations 不能为空");
        }
        state = ContractChecks.requireNonNull(state, "state");
        String canonicalHash = canonicalSemanticHash;
        long matching = observations.stream()
                .filter(item -> item.scheduleSemanticHash().equals(canonicalHash))
                .count();
        boolean hasDifferent = observations.stream()
                .anyMatch(item -> !item.scheduleSemanticHash().equals(canonicalHash));
        if (state == CaseLifecycleState.BASELINE_STABLE
                && (hasDifferent || matching < requiredMatchingRuns)) {
            throw new IllegalArgumentException("BASELINE_STABLE 与运行观察不一致");
        }
        if (state == CaseLifecycleState.BASELINE_UNSTABLE && !hasDifferent) {
            throw new IllegalArgumentException("BASELINE_UNSTABLE 必须包含不同语义结果");
        }
        if (state != CaseLifecycleState.BASELINE_CANDIDATE
                && state != CaseLifecycleState.BASELINE_STABLE
                && state != CaseLifecycleState.BASELINE_UNSTABLE) {
            throw new IllegalArgumentException("BaselineVerification 不支持状态: " + state);
        }
    }
}
