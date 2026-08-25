package org.example.algorithmdebug.staticanalysis;

/**
 * 静态源码分析的确定性容量预算与协作式 deadline。
 *
 * @param maxFiles 最多读取的 Java 文件数
 * @param maxSourceBytes 最多读取的源码总字节数
 * @param maxMethods 最多保存的可达方法数
 * @param maxEdges 最多保存的可达调用边数
 * @param maxCatalogBytes MethodCatalog JSON 的保守 UTF-8 字节上限
 * @param timeoutMillis 可中断扫描阶段和 javac 调用前后的协作式 deadline；不是 hard wall-clock timeout
 */
public record StaticAnalysisBudget(
        int maxFiles,
        long maxSourceBytes,
        int maxMethods,
        int maxEdges,
        long maxCatalogBytes,
        long timeoutMillis) {

    private static final int HARD_MAX_FILES = 10_000;
    private static final long HARD_MAX_BYTES = 64L * 1024 * 1024;
    private static final int HARD_MAX_METHODS = 50_000;
    private static final int HARD_MAX_EDGES = 250_000;
    /** MethodCatalog 预算的最小值，足以容纳契约允许的最坏 warning 预留。 */
    public static final long MIN_CATALOG_BYTES = 16L * 1024 * 1024;
    /** 常规静态分析使用的 MethodCatalog 字节预算。 */
    public static final long DEFAULT_CATALOG_BYTES = 64L * 1024 * 1024;
    /** 与归档 writer 防御上限一致的 MethodCatalog 硬上限。 */
    public static final long MAX_CATALOG_BYTES = 128L * 1024 * 1024;
    private static final long HARD_MAX_TIMEOUT_MILLIS = 10 * 60_000L;

    /** 校验预算为正数且不超过进程安全上限。 */
    public StaticAnalysisBudget {
        if (maxFiles < 1 || maxFiles > HARD_MAX_FILES
                || maxSourceBytes < 1 || maxSourceBytes > HARD_MAX_BYTES
                || maxMethods < 1 || maxMethods > HARD_MAX_METHODS
                || maxEdges < 0 || maxEdges > HARD_MAX_EDGES
                || maxCatalogBytes < MIN_CATALOG_BYTES
                || maxCatalogBytes > MAX_CATALOG_BYTES
                || timeoutMillis < 1 || timeoutMillis > HARD_MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("静态分析预算超出允许范围");
        }
    }

    /** 返回适合普通 Maven 算法模块的默认预算。 */
    public static StaticAnalysisBudget defaults() {
        return new StaticAnalysisBudget(
                5_000, 32L * 1024 * 1024, 20_000, 100_000,
                DEFAULT_CATALOG_BYTES, 60_000);
    }
}
