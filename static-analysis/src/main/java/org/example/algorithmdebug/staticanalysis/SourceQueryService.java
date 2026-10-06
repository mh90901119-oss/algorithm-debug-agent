package org.example.algorithmdebug.staticanalysis;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.MethodCallEdge;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.investigation.SourceQueryCompleteness;
import org.example.algorithmdebug.contracts.investigation.SourceQueryErrorCode;
import org.example.algorithmdebug.contracts.investigation.SourceQueryRequest;
import org.example.algorithmdebug.contracts.investigation.SourceQueryResult;
import org.example.algorithmdebug.contracts.investigation.SourceQueryResult.SourceWindow;

/** 执行六种有界 Source Query，并明确保持“静态关系”语义。 */
public final class SourceQueryService {
    private final BoundedSourceWindowReader sourceReader;
    private final ReachablePathFinder pathFinder;
    private final Clock clock;

    /** 注入源码读取器、路径搜索器和可替换时钟。 */
    public SourceQueryService(
            BoundedSourceWindowReader sourceReader,
            ReachablePathFinder pathFinder,
            Clock clock) {
        if (sourceReader == null || pathFinder == null || clock == null) {
            throw new IllegalArgumentException("SourceQueryService dependencies must not be null");
        }
        this.sourceReader = sourceReader;
        this.pathFinder = pathFinder;
        this.clock = clock;
    }

    /**
     * 基于当前 Analysis 的 Method Catalog 和注册模块根执行确定性查询。
     *
     * @throws SourceQueryException 请求归属错误或源码路径不安全时抛出
     */
    public SourceQueryResult query(
            MethodCatalog catalog,
            Path moduleRoot,
            SourceQueryRequest request) {
        requireIdentity(catalog, request);
        if (moduleRoot == null) {
            throw new IllegalArgumentException("moduleRoot must not be null");
        }
        MethodCatalogIndex index = MethodCatalogIndex.from(catalog);
        QueryAccumulator accumulator = new QueryAccumulator(catalog, request);
        switch (request.mode()) {
            case METHOD -> queryMethod(index, moduleRoot, request, accumulator);
            case CALLERS -> queryEdges(index, request.methodKey().orElseThrow(), true, accumulator);
            case CALLEES -> queryEdges(index, request.methodKey().orElseThrow(), false, accumulator);
            case REACHABLE_PATH -> queryReachablePath(index, request, accumulator);
            case SOURCE_WINDOW -> querySourceWindow(moduleRoot, request, accumulator);
            case SEARCH_SYMBOL -> querySymbol(index, request, accumulator);
        }
        return accumulator.toResult(clock);
    }

    private void queryMethod(
            MethodCatalogIndex index,
            Path moduleRoot,
            SourceQueryRequest request,
            QueryAccumulator accumulator) {
        Optional<MethodCatalogEntry> method = index.method(request.methodKey().orElseThrow());
        if (method.isEmpty()) {
            accumulator.unavailable();
            return;
        }
        accumulator.addMethod(method.orElseThrow());
        addWindow(moduleRoot, method.orElseThrow().sourceAnchor(), request, accumulator);
    }

    private static void queryEdges(
            MethodCatalogIndex index,
            String methodKey,
            boolean callers,
            QueryAccumulator accumulator) {
        if (index.method(methodKey).isEmpty()) {
            accumulator.unavailable();
            return;
        }
        List<MethodCallEdge> edges = callers ? index.incoming(methodKey) : index.outgoing(methodKey);
        for (MethodCallEdge edge : edges) {
            if (!accumulator.addEdge(edge)) {
                break;
            }
            String relatedKey = callers ? edge.callerKey() : edge.calleeKey();
            index.method(relatedKey).ifPresent(accumulator::addMethod);
        }
    }

    private static void querySymbol(
            MethodCatalogIndex index,
            SourceQueryRequest request,
            QueryAccumulator accumulator) {
        for (MethodCatalogEntry method : index.searchSymbol(request.symbol().orElseThrow())) {
            if (!accumulator.addMethod(method)) {
                break;
            }
        }
    }

    private void queryReachablePath(
            MethodCatalogIndex index,
            SourceQueryRequest request,
            QueryAccumulator accumulator) {
        String source = request.methodKey().orElseThrow();
        String target = request.targetMethodKey().orElseThrow();
        if (index.method(source).isEmpty() || index.method(target).isEmpty()) {
            accumulator.unavailable();
            return;
        }
        ReachablePathFinder.Result found = pathFinder.find(index, source, target, request.budget());
        accumulator.addLimitations(found.limitations());
        accumulator.truncated(found.truncated());
        for (List<String> path : found.paths()) {
            if (!accumulator.addPath(path)) {
                break;
            }
            for (String methodKey : path) {
                index.method(methodKey).ifPresent(accumulator::addMethod);
            }
            addPathEdges(index, path, accumulator);
        }
    }

    private static void addPathEdges(
            MethodCatalogIndex index,
            List<String> path,
            QueryAccumulator accumulator) {
        for (int position = 0; position + 1 < path.size(); position++) {
            String callee = path.get(position + 1);
            for (MethodCallEdge edge : index.outgoing(path.get(position))) {
                if (edge.calleeKey().equals(callee) && !accumulator.addEdge(edge)) {
                    return;
                }
            }
        }
    }

    private void querySourceWindow(
            Path moduleRoot,
            SourceQueryRequest request,
            QueryAccumulator accumulator) {
        addWindow(moduleRoot, request.sourceAnchor().orElseThrow(), request, accumulator);
    }

    private void addWindow(
            Path moduleRoot,
            org.example.algorithmdebug.contracts.SourceAnchor anchor,
            SourceQueryRequest request,
            QueryAccumulator accumulator) {
        try {
            BoundedSourceWindowReader.Result result = sourceReader.read(
                    moduleRoot, anchor, request.budget());
            accumulator.addWindow(result.window());
            accumulator.addLimitations(result.limitations());
            accumulator.truncated(result.truncated());
        } catch (SourceQueryException failure) {
            if (failure.code() != SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE) {
                throw failure;
            }
            accumulator.addLimitation(failure.code().name());
            accumulator.unknown();
        }
    }

    private static void requireIdentity(MethodCatalog catalog, SourceQueryRequest request) {
        if (catalog == null || request == null) {
            throw new IllegalArgumentException("Source Query inputs must not be null");
        }
        if (!catalog.caseId().equals(request.caseId())
                || !catalog.analysisId().equals(request.analysisId())) {
            throw new SourceQueryException(
                    SourceQueryErrorCode.SOURCE_QUERY_INVALID_MODE_INPUT,
                    "Source Query identity does not match Method Catalog");
        }
    }

    private static final class QueryAccumulator {
        private final MethodCatalog catalog;
        private final SourceQueryRequest request;
        private final Set<MethodCatalogEntry> methods = new LinkedHashSet<>();
        private final Set<MethodCallEdge> edges = new LinkedHashSet<>();
        private final List<List<String>> paths = new ArrayList<>();
        private final List<SourceWindow> windows = new ArrayList<>();
        private final Set<String> limitations = new LinkedHashSet<>();
        private boolean truncated;
        private boolean unknown;

        private QueryAccumulator(MethodCatalog catalog, SourceQueryRequest request) {
            this.catalog = catalog;
            this.request = request;
            if (catalog.completeness() != SnapshotCompleteness.COMPLETE) {
                limitations.add(SourceQueryErrorCode.SOURCE_QUERY_CATALOG_INCOMPLETE.name());
            }
        }

        private boolean addMethod(MethodCatalogEntry method) {
            if (methods.contains(method)) {
                return true;
            }
            if (methods.size() >= request.budget().maxMethods()) {
                budgetExceeded();
                return false;
            }
            methods.add(method);
            return true;
        }

        private boolean addEdge(MethodCallEdge edge) {
            if (edges.contains(edge)) {
                return true;
            }
            if (edges.size() >= request.budget().maxEdges()) {
                budgetExceeded();
                return false;
            }
            edges.add(edge);
            return true;
        }

        private boolean addPath(List<String> path) {
            if (paths.contains(path)) {
                return true;
            }
            if (paths.size() >= request.budget().maxPaths()) {
                budgetExceeded();
                return false;
            }
            paths.add(List.copyOf(path));
            return true;
        }

        private void addWindow(SourceWindow window) {
            windows.add(window);
        }

        private void unavailable() {
            addLimitation(SourceQueryErrorCode.SOURCE_QUERY_SOURCE_UNAVAILABLE.name());
            unknown = true;
        }

        private void unknown() {
            unknown = true;
        }

        private void truncated(boolean value) {
            truncated |= value;
        }

        private void addLimitations(List<String> values) {
            limitations.addAll(values);
        }

        private void addLimitation(String value) {
            limitations.add(value);
        }

        private void budgetExceeded() {
            truncated = true;
            limitations.add(SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED.name());
        }

        private SourceQueryResult toResult(Clock clock) {
            SourceQueryCompleteness completeness;
            if (unknown) {
                completeness = SourceQueryCompleteness.UNKNOWN;
            } else if (truncated || catalog.completeness() != SnapshotCompleteness.COMPLETE
                    || !limitations.isEmpty()) {
                completeness = SourceQueryCompleteness.PARTIAL;
            } else {
                completeness = SourceQueryCompleteness.COMPLETE;
            }
            return new SourceQueryResult(
                    SchemaVersions.SOURCE_QUERY_RESULT,
                    request.queryId(),
                    request.caseId(),
                    request.analysisId(),
                    request.mode(),
                    completeness,
                    request.methodCatalogArtifact(),
                    List.copyOf(methods),
                    List.copyOf(edges),
                    List.copyOf(paths),
                    List.copyOf(windows),
                    List.copyOf(limitations),
                    truncated,
                    clock.instant());
        }
    }
}
