package org.example.algorithmdebug.contracts;

/**
 * P4 流式归一化的输入、聚合和输出预算。
 *
 * <p>预算用于防止大型算法 Trace 造成内存或上下文膨胀，不对字段内容做权限判断。</p>
 */
public record NormalizationBudget(
        long maxRawBytes,
        int maxRecordBytes,
        long maxRecords,
        int maxMethods,
        int maxRelationships,
        int maxHits,
        int maxFramesPerHit,
        int maxValueFacts,
        int maxScalarChars,
        long maxSummaryBytes) {

    public static final long MAX_RAW_BYTES = 50L * 1024 * 1024;
    public static final int MAX_RECORD_BYTES = 4 * 1024 * 1024;
    public static final long MAX_RECORDS = 1_000_000;
    public static final int MAX_METHODS = 200;
    public static final int MAX_RELATIONSHIPS = 10_000;
    public static final int MAX_HITS = 1_000;
    public static final int MAX_FRAMES_PER_HIT = 64;
    public static final int MAX_VALUE_FACTS = 20_000;
    public static final int MAX_SCALAR_CHARS = 1_024;
    public static final long MAX_SUMMARY_BYTES = 4L * 1024 * 1024;

    /** 校验每个值为正数且不超过 P4 硬上限。 */
    public NormalizationBudget {
        bounded(maxRawBytes, MAX_RAW_BYTES, "maxRawBytes");
        bounded(maxRecordBytes, MAX_RECORD_BYTES, "maxRecordBytes");
        bounded(maxRecords, MAX_RECORDS, "maxRecords");
        bounded(maxMethods, MAX_METHODS, "maxMethods");
        bounded(maxRelationships, MAX_RELATIONSHIPS, "maxRelationships");
        bounded(maxHits, MAX_HITS, "maxHits");
        bounded(maxFramesPerHit, MAX_FRAMES_PER_HIT, "maxFramesPerHit");
        bounded(maxValueFacts, MAX_VALUE_FACTS, "maxValueFacts");
        bounded(maxScalarChars, MAX_SCALAR_CHARS, "maxScalarChars");
        bounded(maxSummaryBytes, MAX_SUMMARY_BYTES, "maxSummaryBytes");
    }

    /** @return 面向常规采集的保守默认预算。 */
    public static NormalizationBudget defaults() {
        return new NormalizationBudget(
                16L * 1024 * 1024, 1024 * 1024, 100_000, 200, 2_000,
                100, 8, 2_000, 256, 512L * 1024);
    }

    private static void bounded(long value, long maximum, String field) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(field + " must be within 1.." + maximum + " range");
        }
    }
}
