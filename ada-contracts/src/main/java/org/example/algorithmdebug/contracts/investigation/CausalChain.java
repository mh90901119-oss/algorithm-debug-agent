package org.example.algorithmdebug.contracts.investigation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 结论 Gate 验证的有界、有向且全部带 provenance 的因果结构。
 *
 * @param schemaVersion Schema 版本
 * @param causalChainId 因果链 ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param nodes 有界节点
 * @param edges 有界有向边
 * @param createdAt 创建时间
 */
public record CausalChain(
        String schemaVersion,
        CausalChainId causalChainId,
        CaseId caseId,
        AnalysisId analysisId,
        List<CausalNode> nodes,
        List<CausalEdge> edges,
        Instant createdAt) {

    /** 校验节点/边唯一性与端点归属。 */
    public CausalChain {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.CAUSAL_CHAIN, "CausalChain");
        causalChainId = InvestigationContractChecks.notNull(causalChainId, "causalChainId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        nodes = InvestigationContractChecks.unique(
                nodes, "nodes", InvestigationLimits.MAX_CAUSAL_NODES);
        edges = InvestigationContractChecks.unique(
                edges, "edges", InvestigationLimits.MAX_CAUSAL_EDGES);
        createdAt = InvestigationContractChecks.notNull(createdAt, "createdAt");
        if (nodes.isEmpty() || edges.isEmpty()) {
            throw new IllegalArgumentException("CausalChain nodes and edges must not be empty");
        }
        Set<String> nodeIds = new HashSet<>();
        nodes.forEach(node -> {
            if (!nodeIds.add(node.nodeId())) {
                throw new IllegalArgumentException("Duplicate CausalNode nodeId");
            }
        });
        Set<String> edgeIds = new HashSet<>();
        edges.forEach(edge -> {
            if (!edgeIds.add(edge.edgeId())) {
                throw new IllegalArgumentException("Duplicate CausalEdge edgeId");
            }
            if (!nodeIds.contains(edge.fromNodeId()) || !nodeIds.contains(edge.toNodeId())) {
                throw new IllegalArgumentException("CausalEdge endpoints must exist in nodes");
            }
        });
    }
}
