package org.example.algorithmdebug.staticanalysis;

import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;

/**
 * MethodCatalog 的保守 JSON UTF-8 字节上界账本。
 *
 * <p>每个 Java UTF-16 code unit 按最坏的六字节 Unicode 转义计算。元素结构开销使用固定余量，
 * 并提前保留契约允许的全部 warning 空间，因此账本上界不依赖 Jackson 的具体非 ASCII 转义策略。</p>
 */
final class CatalogJsonSizeBudget {

    static final int RESERVED_WARNING_COUNT = 1_000;
    static final int MAX_WARNING_CHARACTERS = 2_048;

    private static final long TOP_LEVEL_STRUCTURAL_BYTES = 32L * 1024;
    private static final long METHOD_STRUCTURAL_BYTES = 512;
    private static final long CENSUS_STRUCTURAL_BYTES = 128;
    private static final long EDGE_STRUCTURAL_BYTES = 256;

    private final long maximumBytes;
    private long upperBoundBytes;
    private long attemptedUpperBoundBytes;

    CatalogJsonSizeBudget(StaticAnalysisRequest request) {
        maximumBytes = request.budget().maxCatalogBytes();
        upperBoundBytes = TOP_LEVEL_STRUCTURAL_BYTES
                + jsonStringUpperBound(SchemaVersions.METHOD_CATALOG)
                + jsonStringUpperBound(request.caseId().value())
                + jsonStringUpperBound(request.contextId().value())
                + jsonStringUpperBound(request.analysisId().value())
                + jsonStringUpperBound(request.targetTest().className())
                + jsonStringUpperBound(request.targetTest().methodName())
                + jsonStringUpperBound(request.sourceFingerprintSha256())
                + jsonStringUpperBound(request.requestedAt().toString())
                + RESERVED_WARNING_COUNT
                * (jsonStringUpperBound(MAX_WARNING_CHARACTERS) + 1);
        attemptedUpperBoundBytes = upperBoundBytes;
        if (upperBoundBytes > maximumBytes) {
            throw new StaticAnalysisException(
                    "maxCatalogBytes 无法容纳 MethodCatalog 固定字段和最坏 warning 预留: "
                            + maximumBytes);
        }
    }

    boolean tryMethod(String packageName, String methodKey, SourceAnchor anchor) {
        long candidateBytes = CENSUS_STRUCTURAL_BYTES + jsonStringUpperBound(packageName);
        if (methodKey != null && anchor != null) {
            candidateBytes += METHOD_STRUCTURAL_BYTES
                    + jsonStringUpperBound(methodKey)
                    + jsonStringUpperBound(anchor.className())
                    + jsonStringUpperBound(anchor.methodName())
                    + jsonStringUpperBound(anchor.descriptor())
                    + jsonStringUpperBound(anchor.sourceRelativePath())
                    + jsonStringUpperBound(anchor.sourceSha256());
        }
        return tryAdd(candidateBytes);
    }

    boolean tryEdge(String callerKey, String calleeKey) {
        return tryAdd(EDGE_STRUCTURAL_BYTES
                + jsonStringUpperBound(callerKey)
                + jsonStringUpperBound(calleeKey));
    }

    long upperBoundBytes() {
        return upperBoundBytes;
    }

    long attemptedUpperBoundBytes() {
        return attemptedUpperBoundBytes;
    }

    private boolean tryAdd(long candidateBytes) {
        attemptedUpperBoundBytes = saturatedAdd(upperBoundBytes, candidateBytes);
        if (attemptedUpperBoundBytes > maximumBytes) {
            return false;
        }
        upperBoundBytes = attemptedUpperBoundBytes;
        return true;
    }

    private static long jsonStringUpperBound(String value) {
        return jsonStringUpperBound(value.length());
    }

    private static long jsonStringUpperBound(int utf16Length) {
        return 2L + 6L * utf16Length;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
