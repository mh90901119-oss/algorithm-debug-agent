package org.example.algorithmdebug.contracts.coordination;

import java.util.HashSet;
import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 模型提交给确定性 ConclusionGate 的结论候选。
 *
 * @param schemaVersion Schema 版本
 * @param conclusionId 结论 ID
 * @param identity 分析身份
 * @param basedOnRevision 候选依据的控制状态修订号
 * @param requestedStatus 请求达到的结论等级
 * @param claims 逐条分类且带引用的 Claim
 * @param causalChainIds 因果链 ID
 * @param unresolvedGapIds 模型显式保留的未解决缺口
 */
public record ConclusionCandidate(
        String schemaVersion,
        String conclusionId,
        AnalysisIdentity identity,
        long basedOnRevision,
        ConclusionStatus requestedStatus,
        List<ConclusionClaim> claims,
        List<String> causalChainIds,
        List<String> unresolvedGapIds) {

    /** 校验身份、修订号、Claim 和引用集合。 */
    public ConclusionCandidate {
        CoordinationContractChecks.requireVersion(
                schemaVersion, SchemaVersions.CONCLUSION_CANDIDATE, "ConclusionCandidate");
        conclusionId = CoordinationContractChecks.requireId(conclusionId, "conclusionId");
        identity = CoordinationContractChecks.requireNonNull(identity, "identity");
        if (basedOnRevision < 0) {
            throw new IllegalArgumentException("basedOnRevision must not be negative");
        }
        requestedStatus = CoordinationContractChecks.requireNonNull(
                requestedStatus, "requestedStatus");
        claims = CoordinationContractChecks.immutableUniqueList(
                claims, "claims", CoordinationLimits.MAX_CLAIMS);
        if (claims.isEmpty()) {
            throw new IllegalArgumentException("claims must not be empty");
        }
        if (new HashSet<>(claims.stream().map(ConclusionClaim::claimId).toList()).size()
                != claims.size()) {
            throw new IllegalArgumentException("claims must have unique claimId values");
        }
        causalChainIds = CoordinationContractChecks.immutableUniqueIds(
                causalChainIds, "causalChainIds");
        unresolvedGapIds = CoordinationContractChecks.immutableUniqueIds(
                unresolvedGapIds, "unresolvedGapIds");
    }
}
