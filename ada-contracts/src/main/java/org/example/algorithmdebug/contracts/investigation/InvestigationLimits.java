package org.example.algorithmdebug.contracts.investigation;

/** 调查、Predicate、事件和因果链公共契约的唯一预算来源。 */
public final class InvestigationLimits {
    /** 不透明标识符最大字符数。 */
    public static final int MAX_ID_LENGTH = 128;
    /** 短文本字段最大字符数。 */
    public static final int MAX_SHORT_TEXT_LENGTH = 1_024;
    /** 普通说明文本最大字符数。 */
    public static final int MAX_TEXT_LENGTH = 4_096;
    /** 单字段最大引用数量。 */
    public static final int MAX_REFERENCES = 128;
    /** Problem Frame 最大源码范围锚点数。 */
    public static final int MAX_SCOPE_ANCHORS = 64;
    /** 单次 Investigation 最大假设数。 */
    public static final int MAX_HYPOTHESES = 32;
    /** 单次 Investigation 最大 Evidence Gap 数。 */
    public static final int MAX_GAPS = 64;
    /** 单次 Investigation 最大 Predicate 数。 */
    public static final int MAX_PREDICATES = 128;
    /** 单次 Investigation 最大 Evaluation 数。 */
    public static final int MAX_EVALUATIONS = 512;
    /** 每次采集绑定的最小 Predicate 数。 */
    public static final int MIN_PREDICATES_PER_BINDING = 1;
    /** 每次采集绑定的最大 Predicate 数。 */
    public static final int MAX_PREDICATES_PER_BINDING = 8;
    /** 单条 Causal Chain 最大节点数。 */
    public static final int MAX_CAUSAL_NODES = 64;
    /** 单条 Causal Chain 最大边数。 */
    public static final int MAX_CAUSAL_EDGES = 128;
    /** 单个契约对象最大限制说明数。 */
    public static final int MAX_LIMITATIONS = 128;
    /** SHA-256 十六进制字符串长度。 */
    public static final int SHA256_HEX_LENGTH = 64;

    private InvestigationLimits() {
    }
}
