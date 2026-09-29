package org.example.algorithmdebug.contracts.coordination;

import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * `analysis_finalize` 的唯一服务端完成结果。
 *
 * <p>该契约把模型提交的原始候选与确定性门禁决策绑定在同一个不可变对象中，宿主只能解释这两个已归档对象，
 * 不需要也不应再构造第二套完成状态。它不改变 Gate 的算法语义。</p>
 *
 * @param schemaVersion Schema 版本
 * @param candidate 已提交并归档的完整结论候选
 * @param decision Gate 对该候选作出的确定性决策
 */
public record ConclusionFinalization(
        String schemaVersion,
        ConclusionCandidate candidate,
        ConclusionDecision decision) {

    /** 校验候选与决策属于同一个结论、Analysis 和控制状态修订。 */
    public ConclusionFinalization {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.CONCLUSION_FINALIZATION,
                "ConclusionFinalization");
        candidate = CoordinationContractChecks.requireNonNull(candidate, "candidate");
        decision = CoordinationContractChecks.requireNonNull(decision, "decision");
        if (!candidate.conclusionId().equals(decision.conclusionId())) {
            throw new IllegalArgumentException(
                    "Conclusion finalization requires one conclusionId");
        }
        if (!candidate.identity().equals(decision.identity())) {
            throw new IllegalArgumentException(
                    "Conclusion finalization requires one Analysis identity");
        }
        if (candidate.basedOnRevision() != decision.evaluatedRevision()) {
            throw new IllegalArgumentException(
                    "Conclusion finalization requires one control revision");
        }
    }
}
