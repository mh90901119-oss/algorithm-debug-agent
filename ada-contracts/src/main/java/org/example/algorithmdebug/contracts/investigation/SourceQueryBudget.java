package org.example.algorithmdebug.contracts.investigation;

/**
 * 单次 Source Query 的显式有界预算。
 *
 * @param maxMethods 最大方法数
 * @param maxEdges 最大调用边数
 * @param maxDepth 最大搜索深度
 * @param maxPaths 最大路径数
 * @param maxSourceLines 最大源码行数
 * @param maxResponseBytes 最大 UTF-8 响应字节数
 */
public record SourceQueryBudget(
        int maxMethods,
        int maxEdges,
        int maxDepth,
        int maxPaths,
        int maxSourceLines,
        int maxResponseBytes) {

    /** 拒绝非正值和超过硬上限的请求。 */
    public SourceQueryBudget {
        requireRange(maxMethods, SourceQueryLimits.MAX_METHODS, "maxMethods");
        requireRange(maxEdges, SourceQueryLimits.MAX_EDGES, "maxEdges");
        requireRange(maxDepth, SourceQueryLimits.MAX_DEPTH, "maxDepth");
        requireRange(maxPaths, SourceQueryLimits.MAX_PATHS, "maxPaths");
        requireRange(maxSourceLines, SourceQueryLimits.MAX_SOURCE_LINES, "maxSourceLines");
        requireRange(maxResponseBytes, SourceQueryLimits.MAX_RESPONSE_BYTES, "maxResponseBytes");
    }

    /** @return 设计冻结的默认查询预算 */
    public static SourceQueryBudget defaults() {
        return new SourceQueryBudget(
                SourceQueryLimits.DEFAULT_METHODS,
                SourceQueryLimits.DEFAULT_EDGES,
                SourceQueryLimits.DEFAULT_DEPTH,
                SourceQueryLimits.DEFAULT_PATHS,
                SourceQueryLimits.DEFAULT_SOURCE_LINES,
                SourceQueryLimits.DEFAULT_RESPONSE_BYTES);
    }

    private static void requireRange(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maximum);
        }
    }
}
