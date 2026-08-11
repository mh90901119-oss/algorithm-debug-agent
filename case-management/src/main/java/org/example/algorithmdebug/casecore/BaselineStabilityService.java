package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.BaselineRunObservation;
import org.example.algorithmdebug.contracts.BaselineVerification;
import org.example.algorithmdebug.contracts.CaseFingerprint;
import org.example.algorithmdebug.contracts.CaseLifecycleState;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;

import java.util.ArrayList;
import java.util.List;

/** 根据同一运行前身份的语义哈希序列判断 Baseline 是否稳定。 */
public final class BaselineStabilityService {

    private final int requiredMatchingRuns;

    /** @param requiredMatchingRuns 判定稳定所需匹配次数，至少为 2 */
    public BaselineStabilityService(int requiredMatchingRuns) {
        if (requiredMatchingRuns < 2) {
            throw new IllegalArgumentException("requiredMatchingRuns 不能小于 2");
        }
        this.requiredMatchingRuns = requiredMatchingRuns;
    }

    /** 记录同一 Case 的第一次成功 Baseline。 */
    public BaselineVerification start(
            CaseFingerprint fingerprint,
            RunId runId,
            String semanticHash) {
        return new BaselineVerification(
                SchemaVersions.BASELINE_VERIFICATION,
                fingerprint,
                semanticHash,
                requiredMatchingRuns,
                List.of(new BaselineRunObservation(runId, semanticHash)),
                CaseLifecycleState.BASELINE_CANDIDATE);
    }

    /** 追加一次运行观察；出现任何不同语义结果后状态保持不稳定。 */
    public BaselineVerification record(
            BaselineVerification current,
            RunId runId,
            String semanticHash) {
        if (current == null || runId == null || semanticHash == null) {
            throw new IllegalArgumentException("Baseline 追加参数不能为空");
        }
        if (current.requiredMatchingRuns() != requiredMatchingRuns) {
            throw new IllegalArgumentException("Baseline 匹配阈值与 Service 配置不一致");
        }
        if (current.observations().stream().anyMatch(item -> item.runId().equals(runId))) {
            throw new IllegalArgumentException("同一个 runId 不能重复记录: " + runId.value());
        }
        List<BaselineRunObservation> observations = new ArrayList<>(current.observations());
        observations.add(new BaselineRunObservation(runId, semanticHash));
        boolean different = observations.stream().anyMatch(
                item -> !item.scheduleSemanticHash().equals(current.canonicalSemanticHash()));
        long matching = observations.stream().filter(
                item -> item.scheduleSemanticHash().equals(current.canonicalSemanticHash())).count();
        CaseLifecycleState state = different
                ? CaseLifecycleState.BASELINE_UNSTABLE
                : matching >= requiredMatchingRuns
                        ? CaseLifecycleState.BASELINE_STABLE
                        : CaseLifecycleState.BASELINE_CANDIDATE;
        return new BaselineVerification(
                SchemaVersions.BASELINE_VERIFICATION,
                current.caseFingerprint(),
                current.canonicalSemanticHash(),
                requiredMatchingRuns,
                observations,
                state);
    }
}
