package org.example.algorithmdebug.staticanalysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.example.algorithmdebug.contracts.MethodCallEdge;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryErrorCode;

/** 对 Method Catalog 执行有界、确定性的广度优先可达路径搜索。 */
public final class ReachablePathFinder {
    /**
     * 搜索从 sourceMethodKey 到 targetMethodKey 的稳定路径前缀。
     *
     * @return 不超过请求预算的路径及截断原因
     */
    public Result find(
            MethodCatalogIndex index,
            String sourceMethodKey,
            String targetMethodKey,
            SourceQueryBudget budget) {
        if (index == null || sourceMethodKey == null || targetMethodKey == null || budget == null) {
            throw new IllegalArgumentException("Reachable path inputs must not be null");
        }
        if (index.method(sourceMethodKey).isEmpty() || index.method(targetMethodKey).isEmpty()) {
            return new Result(List.of(), false, List.of());
        }

        ArrayDeque<List<String>> pending = new ArrayDeque<>();
        pending.add(List.of(sourceMethodKey));
        Set<String> encounteredMethods = new LinkedHashSet<>();
        encounteredMethods.add(sourceMethodKey);
        List<List<String>> paths = new ArrayList<>();
        boolean truncated = false;
        int traversedEdges = 0;

        search:
        while (!pending.isEmpty()) {
            List<String> path = pending.removeFirst();
            String current = path.getLast();
            int depth = path.size() - 1;
            if (current.equals(targetMethodKey)) {
                if (paths.size() < budget.maxPaths()) {
                    paths.add(path);
                } else {
                    truncated = true;
                    break;
                }
                continue;
            }
            List<MethodCallEdge> nextEdges = index.outgoing(current);
            if (depth >= budget.maxDepth()) {
                if (nextEdges.stream().anyMatch(
                        edge -> !path.contains(edge.calleeKey()))) {
                    truncated = true;
                }
                continue;
            }
            Set<String> expandedCallees = new LinkedHashSet<>();
            for (MethodCallEdge edge : nextEdges) {
                if (traversedEdges >= budget.maxEdges()) {
                    truncated = true;
                    break search;
                }
                traversedEdges++;
                String next = edge.calleeKey();
                if (!expandedCallees.add(next)) {
                    continue;
                }
                if (path.contains(next)) {
                    continue;
                }
                if (!encounteredMethods.contains(next)
                        && encounteredMethods.size() >= budget.maxMethods()) {
                    truncated = true;
                    continue;
                }
                encounteredMethods.add(next);
                List<String> extended = new ArrayList<>(path.size() + 1);
                extended.addAll(path);
                extended.add(next);
                pending.addLast(List.copyOf(extended));
            }
        }
        List<String> limitations = truncated
                ? List.of(SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED.name())
                : List.of();
        return new Result(paths, truncated, limitations);
    }

    /** 稳定路径搜索结果。 */
    public record Result(List<List<String>> paths, boolean truncated, List<String> limitations) {
        /** 防止调用方修改搜索结果。 */
        public Result {
            paths = paths.stream().map(List::copyOf).toList();
            limitations = List.copyOf(limitations);
        }
    }
}
