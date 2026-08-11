package org.example.algorithmdebug.contracts;

/**
 * 已发布契约的当前 Schema 版本。
 *
 * <p>版本常量集中定义，避免各模块写死互相不一致的字符串。</p>
 */
public final class SchemaVersions {

    /** 无采集基线清单 Schema 版本。 */
    public static final String BASELINE_MANIFEST = "2.0";

    /** Baseline 重复验证状态 Schema 版本。 */
    public static final String BASELINE_VERIFICATION = "1.0";

    /** 统一工具响应 Schema 版本。 */
    public static final String TOOL_RESPONSE = "2.0";

    /** 面向模型的目标 UT 运行摘要 Schema 版本。 */
    public static final String RUN_OUTCOME_SUMMARY = "1.0";

    private SchemaVersions() {
    }
}
