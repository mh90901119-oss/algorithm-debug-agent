package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从目标 UT 出发构建的有界静态方法与调用边目录。
 * 每个方法仍保留精确 {@link SourceAnchor}，目录本身不绑定整模块源码指纹。
 */
public record MethodCatalog(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<MethodCatalogEntry> entries,
        List<MethodCallEdge> edges,
        List<String> warnings,
        SnapshotCompleteness completeness,
        int discoveredMethodCount,
        int discoveredEdgeCount,
        Instant createdAt) {

    private static final int MAX_METHODS = 50_000;
    private static final int MAX_EDGES = 250_000;
    private static final int MAX_WARNINGS = 1_000;

    /** 校验身份、预算、目标方法唯一性、边端点和截断计数。 */
    public MethodCatalog {
        if (!SchemaVersions.METHOD_CATALOG.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported MethodCatalog schemaVersion");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        entries = ContractChecks.immutableList(entries, "entries");
        edges = ContractChecks.immutableList(edges, "edges");
        warnings = ContractChecks.immutableBoundedStrings(warnings, "warnings", 2_048);
        completeness = ContractChecks.requireNonNull(completeness, "completeness");
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
        if (entries.isEmpty() || entries.size() > MAX_METHODS) {
            throw new IllegalArgumentException("entries count must be between 1 and " + MAX_METHODS + " ");
        }
        if (edges.size() > MAX_EDGES || warnings.size() > MAX_WARNINGS) {
            throw new IllegalArgumentException("MethodCatalog list exceeds the hard limit");
        }
        Set<String> methodKeys = new HashSet<>();
        for (MethodCatalogEntry entry : entries) {
            if (!methodKeys.add(entry.methodKey())) {
                throw new IllegalArgumentException("entries contains a duplicate methodKey: " + entry.methodKey());
            }
        }
        long targets = entries.stream().filter(MethodCatalogEntry::targetMethod).count();
        TargetTest checkedTarget = targetTest;
        if (targets != 1 || entries.stream().filter(MethodCatalogEntry::targetMethod).noneMatch(
                entry -> entry.sourceAnchor().className().equals(checkedTarget.className())
                        && entry.sourceAnchor().methodName().equals(checkedTarget.methodName()))) {
            throw new IllegalArgumentException("MethodCatalog must contain exactly one matching targetTest targetmethod");
        }
        for (MethodCallEdge edge : edges) {
            if (!methodKeys.contains(edge.callerKey()) || !methodKeys.contains(edge.calleeKey())) {
                throw new IllegalArgumentException("Call-edge endpoints must exist in MethodCatalog entries");
            }
        }
        if (discoveredMethodCount < entries.size() || discoveredEdgeCount < edges.size()) {
            throw new IllegalArgumentException("discovered count must not be less than the retained list size");
        }
    }
}
