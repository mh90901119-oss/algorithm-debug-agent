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
            throw new IllegalArgumentException("不支持的 MethodCatalog schemaVersion");
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
            throw new IllegalArgumentException("entries 数量必须在 1 到 " + MAX_METHODS + " 之间");
        }
        if (edges.size() > MAX_EDGES || warnings.size() > MAX_WARNINGS) {
            throw new IllegalArgumentException("MethodCatalog 列表超过硬上限");
        }
        Set<String> methodKeys = new HashSet<>();
        for (MethodCatalogEntry entry : entries) {
            if (!methodKeys.add(entry.methodKey())) {
                throw new IllegalArgumentException("entries 包含重复 methodKey: " + entry.methodKey());
            }
        }
        long targets = entries.stream().filter(MethodCatalogEntry::targetMethod).count();
        TargetTest checkedTarget = targetTest;
        if (targets != 1 || entries.stream().filter(MethodCatalogEntry::targetMethod).noneMatch(
                entry -> entry.sourceAnchor().className().equals(checkedTarget.className())
                        && entry.sourceAnchor().methodName().equals(checkedTarget.methodName()))) {
            throw new IllegalArgumentException("MethodCatalog 必须包含唯一且匹配 targetTest 的目标方法");
        }
        for (MethodCallEdge edge : edges) {
            if (!methodKeys.contains(edge.callerKey()) || !methodKeys.contains(edge.calleeKey())) {
                throw new IllegalArgumentException("调用边端点必须存在于 MethodCatalog entries");
            }
        }
        if (discoveredMethodCount < entries.size() || discoveredEdgeCount < edges.size()) {
            throw new IllegalArgumentException("discovered 计数不能小于已保存列表");
        }
    }
}
