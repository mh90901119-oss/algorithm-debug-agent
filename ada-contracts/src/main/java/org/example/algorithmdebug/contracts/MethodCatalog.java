package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从一个目标 UT 出发构建的有界静态方法与调用边目录。
 *
 * <p>目录只保存目标模块源码中能够建立稳定 {@link SourceAnchor} 的方法。无法解析的外部调用进入
 * warnings；发生预算截断时 completeness 必须为 {@link SnapshotCompleteness#INCOMPLETE}，并通过
 * discovered 计数保留被截断规模。</p>
 *
 * @param schemaVersion Schema 版本
 * @param caseId 所属 Case
 * @param contextId 所属源码 Context
 * @param analysisId 请求本次静态分析的 Analysis
 * @param targetTest 目标 UT
 * @param sourceFingerprintSha256 Context 源码快照 Hash
 * @param entries 有界方法目录
 * @param edges 有界静态调用边
 * @param warnings 有界解析或截断警告
 * @param packageCensus 实际扫描到的精确 package 方法计数
 * @param completeness 是否完整覆盖约定预算内源码
 * @param packageCensusCompleteness package census 是否完整覆盖全部选定源码
 * @param discoveredMethodCount 截断前已发现的方法数
 * @param discoveredEdgeCount 截断前已发现的调用边数
 * @param createdAt 创建时间
 */
public record MethodCatalog(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        String sourceFingerprintSha256,
        List<MethodCatalogEntry> entries,
        List<MethodCallEdge> edges,
        List<String> warnings,
        List<PackageCensusEntry> packageCensus,
        SnapshotCompleteness completeness,
        SnapshotCompleteness packageCensusCompleteness,
        int discoveredMethodCount,
        int discoveredEdgeCount,
        Instant createdAt) {

    private static final int MAX_METHODS = 50_000;
    private static final int MAX_EDGES = 250_000;
    private static final int MAX_WARNINGS = 1_000;
    private static final int MAX_PACKAGE_CENSUS = 50_000;

    /** 校验身份、预算、目标方法唯一性、边端点和截断计数。 */
    public MethodCatalog {
        schemaVersion = requireVersion(schemaVersion);
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        sourceFingerprintSha256 = ContractChecks.requireSha256(
                sourceFingerprintSha256, "sourceFingerprintSha256");
        entries = ContractChecks.immutableList(entries, "entries");
        edges = ContractChecks.immutableList(edges, "edges");
        warnings = ContractChecks.immutableBoundedStrings(warnings, "warnings", 2_048);
        packageCensus = ContractChecks.immutableList(packageCensus, "packageCensus");
        completeness = ContractChecks.requireNonNull(completeness, "completeness");
        packageCensusCompleteness = ContractChecks.requireNonNull(
                packageCensusCompleteness, "packageCensusCompleteness");
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
        if (entries.isEmpty() || entries.size() > MAX_METHODS) {
            throw new IllegalArgumentException("entries 数量必须在 1 到 " + MAX_METHODS + " 之间");
        }
        if (edges.size() > MAX_EDGES || warnings.size() > MAX_WARNINGS) {
            throw new IllegalArgumentException("MethodCatalog 列表超过硬上限");
        }
        if (packageCensus.isEmpty() || packageCensus.size() > MAX_PACKAGE_CENSUS) {
            throw new IllegalArgumentException("packageCensus 数量必须在 1 到 "
                    + MAX_PACKAGE_CENSUS + " 之间");
        }
        String previousPackage = null;
        long censusMethodCount = 0;
        for (PackageCensusEntry censusEntry : packageCensus) {
            if (previousPackage != null
                    && previousPackage.compareTo(censusEntry.packageName()) >= 0) {
                throw new IllegalArgumentException("packageCensus 必须按 packageName 严格升序且不重复");
            }
            previousPackage = censusEntry.packageName();
            censusMethodCount += censusEntry.methodCount();
        }
        Set<String> methodKeys = new HashSet<>();
        for (MethodCatalogEntry entry : entries) {
            if (!methodKeys.add(entry.methodKey())) {
                throw new IllegalArgumentException("entries 包含重复 methodKey: " + entry.methodKey());
            }
        }
        long targets = entries.stream().filter(MethodCatalogEntry::targetMethod).count();
        TargetTest checkedTargetTest = targetTest;
        if (targets != 1 || entries.stream().filter(MethodCatalogEntry::targetMethod).noneMatch(
                entry -> entry.sourceAnchor().className().equals(checkedTargetTest.className())
                        && entry.sourceAnchor().methodName().equals(checkedTargetTest.methodName()))) {
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
        if (censusMethodCount > discoveredMethodCount
                || (packageCensusCompleteness == SnapshotCompleteness.COMPLETE
                && censusMethodCount != discoveredMethodCount)) {
            throw new IllegalArgumentException("packageCensus 与 discoveredMethodCount 不一致");
        }
    }

    private static String requireVersion(String version) {
        String checked = ContractChecks.requireNonBlank(version, "schemaVersion");
        if (!SchemaVersions.METHOD_CATALOG.equals(checked)) {
            throw new IllegalArgumentException("不支持的 MethodCatalog schemaVersion: " + checked);
        }
        return checked;
    }
}
