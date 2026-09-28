package org.example.algorithmdebug.contracts.coordination;

/**
 * Core Action 输入预算的唯一公共来源。
 *
 * <p>入口适配器、Core typed payload 与对外 Schema 必须使用这些边界，禁止各自复制数值。</p>
 */
public final class ActionInputLimits {
    /** Adapter 标识最大字符数。 */
    public static final int MAX_ADAPTER_ID_LENGTH = 512;
    /** Artifact 标识最大字符数。 */
    public static final int MAX_ARTIFACT_ID_LENGTH = 256;
    /** Gantt 操作名称最大字符数。 */
    public static final int MAX_GANTT_OPERATION_LENGTH = 32;
    /** JSON Pointer 最大字符数。 */
    public static final int MAX_JSON_POINTER_LENGTH = 4_096;
    /** Gantt 单次读取最大行数。 */
    public static final int MAX_GANTT_ROWS = 100;
    /** Artifact 单次读取最大字节数。 */
    public static final int MAX_ARTIFACT_READ_BYTES = 1_048_576;

    private ActionInputLimits() {
    }
}
