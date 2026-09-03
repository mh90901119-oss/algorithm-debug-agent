package org.example.algorithmdebug.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 将模型提出的 JDWP 采集意图绑定到当前 MethodCatalog 和模块内真实源码位置。 */
public final class JdwpPlanCompiler {

    /**
     * 解析方法身份、校验源码路径和断点行范围，并生成确定性排序的 Agent JDWP Plan。
     *
     * @param catalog 当前 Case/Analysis 的静态方法目录
     * @param request 大模型提出的采集意图
     * @param moduleRoot 已登记 Maven 算法模块根目录
     * @return 可归档且只包含当前 Collector 能力的计划
     * @throws PlanCompilationException 请求不安全、源码漂移或文件读取失败
     */
    public JdwpCollectionPlan compile(
            MethodCatalog catalog, JdwpPlanRequest request, Path moduleRoot) {
        if (catalog == null || request == null || moduleRoot == null) {
            throw new IllegalArgumentException("catalog, request and moduleRoot must not be null");
        }
        if (request.tracepoints().isEmpty() || request.tracepoints().size() > 20) {
            throw new PlanCompilationException("The JDWP plan must contain between 1 and 20 tracepoints");
        }
        Map<String, MethodCatalogEntry> entries = catalog.entries().stream().collect(
                Collectors.toUnmodifiableMap(MethodCatalogEntry::methodKey, Function.identity()));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JdwpTracepointRequest point : request.tracepoints()) {
            if (!ids.add(point.tracepointId())) {
                throw new PlanCompilationException("tracepointId must not be duplicated: " + point.tracepointId());
            }
        }
        Path realRoot = realModuleRoot(moduleRoot);
        var points = request.tracepoints().stream()
                .sorted(Comparator.comparing(JdwpTracepointRequest::tracepointId))
                .map(point -> compilePoint(entries, point, realRoot))
                .toList();
        try {
            return new JdwpCollectionPlan(
                    SchemaVersions.JDWP_COLLECTION_PLAN,
                    request.planId(), catalog.caseId(), catalog.analysisId(),
                    catalog.targetTest(), points,
                    request.budget(), request.rationale(), request.intent(), request.requestedAt());
        } catch (IllegalArgumentException failure) {
            throw new PlanCompilationException("The JDWP plan violates the safety contract: " + failure.getMessage(), failure);
        }
    }

    private static JdwpTracepointSpec compilePoint(
            Map<String, MethodCatalogEntry> entries,
            JdwpTracepointRequest request,
            Path realRoot) {
        MethodCatalogEntry entry = entries.get(request.methodKey());
        if (entry == null) {
            throw new PlanCompilationException(
                    "The selected method does not belong to the current MethodCatalog: " + request.methodKey());
        }
        var anchor = entry.sourceAnchor();
        Path source = resolveSource(realRoot, anchor.sourceRelativePath());
        try {
            return new JdwpTracepointSpec(
                    request.tracepointId(), entry.methodKey(), anchor,
                    request.line(), request.maxObservedHits(), request.maxCapturedHits(),
                    request.captureFirstMatchedHits(), request.captureEveryMatchedHits(),
                    request.conditions(), request.capture());
        } catch (IllegalArgumentException failure) {
            throw new PlanCompilationException(
                    "JDWP tracepoint is invalid " + request.tracepointId() + ": " + failure.getMessage(),
                    failure);
        }
    }

    private static Path realModuleRoot(Path moduleRoot) {
        try {
            Path root = moduleRoot.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(root)) {
                throw new PlanCompilationException("The Maven module root is not a directory: " + moduleRoot);
            }
            return root;
        } catch (IOException failure) {
            throw new PlanCompilationException("Failed to resolve the Maven module root: " + moduleRoot, failure);
        }
    }

    private static Path resolveSource(Path realRoot, String relativePath) {
        Path candidate = realRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(realRoot)) {
            throw new PlanCompilationException("The source path escapes the Maven module: " + relativePath);
        }
        try {
            Path realSource = candidate.toRealPath();
            if (!realSource.startsWith(realRoot) || !Files.isRegularFile(realSource)) {
                throw new PlanCompilationException("The source is not a regular file inside the module: " + relativePath);
            }
            return realSource;
        } catch (IOException failure) {
            throw new PlanCompilationException("Failed to read the JDWP anchor source: " + relativePath, failure);
        }
    }

}
