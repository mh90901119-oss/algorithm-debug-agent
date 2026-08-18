package org.example.algorithmdebug.plan;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.JavaPackageScope;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PackageCensusEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;

/** 将模型给出的关键方法选择确定性编译成 CodePath 采集计划。 */
public final class CodePathPlanCompiler {

    private static final long CONSERVATIVE_EVENTS_PER_SOURCE_METHOD = 10_000;

    /**
     * 验证选择器属于当前目录，并派生外部工具所需的包级超集。
     *
     * @param catalog 当前源码 Context 的静态目录
     * @param request 有界方法选择请求
     * @return 可归档、可执行的计划
     * @throws PlanCompilationException 方法不存在、选择过多或超集成本不安全
     */
    public CodePathCollectionPlan compile(MethodCatalog catalog, CodePathPlanRequest request) {
        String rationale = request.rationale().strip();
        if (rationale.isEmpty() || rationale.length() > 4_096) {
            throw new PlanCompilationException("CodePath 计划 rationale 必须在 1 到 4096 字符之间");
        }
        if (request.selectedMethodKeys().isEmpty() || request.selectedMethodKeys().size() > 200) {
            throw new PlanCompilationException("CodePath 计划必须选择 1 到 200 个方法");
        }
        if (request.estimatedPackageEvents() < 0 || request.estimatedPackageEvents() > 1_000_000) {
            throw new PlanCompilationException("包级超集预计事件数超过 1,000,000，拒绝执行");
        }
        Map<String, MethodCatalogEntry> entries = catalog.entries().stream().collect(
                Collectors.toMap(MethodCatalogEntry::methodKey, Function.identity()));
        LinkedHashSet<String> uniqueKeys = new LinkedHashSet<>(request.selectedMethodKeys());
        if (uniqueKeys.size() != request.selectedMethodKeys().size()) {
            throw new PlanCompilationException("selectedMethodKeys 不得重复");
        }
        List<MethodSelector> selectors = uniqueKeys.stream().map(key -> {
            MethodCatalogEntry entry = entries.get(key);
            if (entry == null) {
                throw new PlanCompilationException("选择的方法不属于当前 MethodCatalog: " + key);
            }
            var anchor = entry.sourceAnchor();
            return new MethodSelector(
                    key, anchor.className(), anchor.methodName(), anchor.descriptor(), anchor.sourceSha256());
        }).sorted(Comparator.comparing(MethodSelector::methodKey)).toList();
        List<String> selectedPackages = selectors.stream()
                .map(MethodSelector::className)
                .map(CodePathPlanCompiler::packageName)
                .distinct()
                .sorted()
                .toList();
        if (selectedPackages.size() != 1) {
            throw new PlanCompilationException(
                    "当前 CodePath 工具单次只支持一个精确 package，拒绝跨 package 计划");
        }
        if (catalog.packageCensusCompleteness() != SnapshotCompleteness.COMPLETE) {
            throw new PlanCompilationException("MethodCatalog package census 不完整，拒绝估算采集成本");
        }
        String selectedPackage = selectedPackages.getFirst();
        long packageMethodCount = catalog.packageCensus().stream()
                .filter(entry -> JavaPackageScope.contains(
                        selectedPackage, entry.packageName()))
                .mapToLong(PackageCensusEntry::methodCount)
                .sum();
        if (packageMethodCount == 0) {
            throw new PlanCompilationException(
                    "所选方法的 package 边界树不存在于 MethodCatalog census");
        }
        if (packageMethodCount > 1_000_000 / CONSERVATIVE_EVENTS_PER_SOURCE_METHOD) {
            throw new PlanCompilationException("静态目录推导的包级超集预计事件数超过 1,000,000，拒绝执行");
        }
        long deterministicEstimate = packageMethodCount * CONSERVATIVE_EVENTS_PER_SOURCE_METHOD;
        long estimatedPackageEvents = Math.max(
                request.estimatedPackageEvents(), deterministicEstimate);
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN,
                request.planId(), catalog.caseId(), catalog.contextId(), catalog.analysisId(),
                catalog.targetTest(), catalog.sourceFingerprintSha256(), selectors,
                List.of(selectedPackage),
                "PACKAGE_SUPERSET", request.budget(), estimatedPackageEvents,
                rationale, request.requestedAt());
    }

    private static String packageName(String className) {
        int separator = className.lastIndexOf('.');
        if (separator < 1) {
            throw new PlanCompilationException("默认包不受 CodePath 采集支持: " + className);
        }
        return className.substring(0, separator);
    }

}
