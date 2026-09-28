package org.example.algorithmdebug.contracts.investigation;

import java.util.List;
import org.example.algorithmdebug.contracts.EvidenceId;

/**
 * 因果链中的症状、运行时状态、决策或源码机制节点。
 *
 * @param nodeId 节点 ID
 * @param type 节点类型
 * @param label 有界说明
 * @param evidenceIds 动态 Evidence 引用
 * @param sourceQueryIds 静态 Source Query 引用
 */
public record CausalNode(
        String nodeId,
        CausalNodeType type,
        String label,
        List<EvidenceId> evidenceIds,
        List<SourceQueryId> sourceQueryIds) {

    /** 校验节点 ID、文本和引用唯一性。 */
    public CausalNode {
        nodeId = InvestigationContractChecks.id(nodeId, "nodeId");
        type = InvestigationContractChecks.notNull(type, "type");
        label = InvestigationContractChecks.text(
                label, "label", InvestigationLimits.MAX_TEXT_LENGTH);
        evidenceIds = InvestigationContractChecks.unique(
                evidenceIds, "evidenceIds", InvestigationLimits.MAX_REFERENCES);
        sourceQueryIds = InvestigationContractChecks.unique(
                sourceQueryIds, "sourceQueryIds", InvestigationLimits.MAX_REFERENCES);
        if (evidenceIds.isEmpty() && sourceQueryIds.isEmpty()) {
            throw new IllegalArgumentException("A CausalNode requires a source or Evidence reference");
        }
    }
}
