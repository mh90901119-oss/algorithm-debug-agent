package org.example.algorithmdebug.contracts.coordination;

import java.util.HashSet;
import java.util.List;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;

/**
 * 结论候选中的单条 typed Claim 及其显式来源引用。
 *
 * @param claimId Claim ID
 * @param statement 有界结论陈述
 * @param classification 来源等级
 * @param evidenceIds 动态 Evidence ID
 * @param artifactReferences 可校验 Artifact 引用
 * @param sourceReferenceIds 源码查询或调查对象 ID
 */
public record ConclusionClaim(
        String claimId,
        String statement,
        ClaimClassification classification,
        List<EvidenceId> evidenceIds,
        List<ArtifactReference> artifactReferences,
        List<String> sourceReferenceIds) {

    /** 校验 Claim 分类、引用唯一性，并禁止无引用陈述。 */
    public ConclusionClaim {
        claimId = CoordinationContractChecks.requireId(claimId, "claimId");
        statement = CoordinationContractChecks.requireText(
                statement, "statement", CoordinationLimits.MAX_CLAIM_TEXT_LENGTH, false);
        classification = CoordinationContractChecks.requireNonNull(
                classification, "classification");
        evidenceIds = CoordinationContractChecks.immutableUniqueList(
                evidenceIds, "evidenceIds", CoordinationLimits.MAX_CONTROL_ITEMS);
        artifactReferences = CoordinationContractChecks.immutableUniqueList(
                artifactReferences, "artifactReferences", CoordinationLimits.MAX_ARTIFACTS);
        if (new HashSet<>(artifactReferences.stream()
                .map(ArtifactReference::artifactId).toList()).size() != artifactReferences.size()) {
            throw new IllegalArgumentException(
                    "artifactReferences must have unique artifactId values");
        }
        sourceReferenceIds = CoordinationContractChecks.immutableUniqueIds(
                sourceReferenceIds, "sourceReferenceIds");
        if (evidenceIds.isEmpty() && artifactReferences.isEmpty() && sourceReferenceIds.isEmpty()) {
            throw new IllegalArgumentException("A conclusion claim requires at least one reference");
        }
    }
}
