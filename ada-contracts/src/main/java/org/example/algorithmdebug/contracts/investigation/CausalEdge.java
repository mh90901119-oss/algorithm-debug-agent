package org.example.algorithmdebug.contracts.investigation;

import java.util.List;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.EvidenceId;

/**
 * 因果节点间带分类和 provenance 的有向关系。
 *
 * @param edgeId 边 ID
 * @param fromNodeId 起点节点
 * @param toNodeId 终点节点
 * @param relation 关系说明
 * @param classification 来源分类
 * @param critical 是否属于关键因果路径
 * @param evidenceIds 动态 Evidence 引用
 * @param sourceQueryIds 静态 Source Query 引用
 */
public record CausalEdge(
        String edgeId,
        String fromNodeId,
        String toNodeId,
        String relation,
        ClaimClassification classification,
        boolean critical,
        List<EvidenceId> evidenceIds,
        List<SourceQueryId> sourceQueryIds) {

    /** 校验端点、分类和至少一种可追溯引用。 */
    public CausalEdge {
        edgeId = InvestigationContractChecks.id(edgeId, "edgeId");
        fromNodeId = InvestigationContractChecks.id(fromNodeId, "fromNodeId");
        toNodeId = InvestigationContractChecks.id(toNodeId, "toNodeId");
        if (fromNodeId.equals(toNodeId)) {
            throw new IllegalArgumentException("A CausalEdge cannot point to itself");
        }
        relation = InvestigationContractChecks.text(
                relation, "relation", InvestigationLimits.MAX_SHORT_TEXT_LENGTH);
        classification = InvestigationContractChecks.notNull(classification, "classification");
        evidenceIds = InvestigationContractChecks.unique(
                evidenceIds, "evidenceIds", InvestigationLimits.MAX_REFERENCES);
        sourceQueryIds = InvestigationContractChecks.unique(
                sourceQueryIds, "sourceQueryIds", InvestigationLimits.MAX_REFERENCES);
        if (evidenceIds.isEmpty() && sourceQueryIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "A CausalEdge requires Evidence or Source Query provenance");
        }
    }
}
