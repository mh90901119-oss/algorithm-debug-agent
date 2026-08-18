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

    /** Case 身份清单 Schema 版本。 */
    public static final String CASE_MANIFEST = "1.0";

    /** Context 内容快照 Schema 版本。 */
    public static final String CONTEXT_SNAPSHOT = "1.0";

    /** Analysis 请求 Schema 版本。 */
    public static final String ANALYSIS_REQUEST = "1.0";

    /** Run 启动请求 Schema 版本。 */
    public static final String RUN_REQUEST = "1.0";

    /** Run 确定性结果指纹 Schema 版本。 */
    public static final String RUN_RESULT_FINGERPRINT = "1.0";

    /** 面向模型的有界 Case 摘要 Schema 版本。 */
    public static final String CASE_DIGEST = "1.0";

    /** Agent Workspace 清单 Schema 版本。 */
    public static final String WORKSPACE_MANIFEST = "1.0";

    /** 目标算法项目注册信息 Schema 版本。 */
    public static final String PROJECT_REGISTRATION = "1.0";

    /** 环境诊断报告 Schema 版本。 */
    public static final String DOCTOR_REPORT = "1.0";

    /** 目标 UT 静态方法目录 Schema 版本。 */
    public static final String METHOD_CATALOG = "1.0";
    public static final String CODEPATH_COLLECTION_PLAN = "1.0";

    private SchemaVersions() {
    }
}
