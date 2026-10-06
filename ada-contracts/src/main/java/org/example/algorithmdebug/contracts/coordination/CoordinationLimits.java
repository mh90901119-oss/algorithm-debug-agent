package org.example.algorithmdebug.contracts.coordination;

/** Coordination 公共契约使用的统一数量和文本预算。 */
public final class CoordinationLimits {
    /** 单个不透明 ID 的最大字符数。 */
    public static final int MAX_ID_LENGTH = 128;
    /** Workspace 标识的最大字符数。 */
    public static final int MAX_WORKSPACE_ID_LENGTH = 256;
    /** Policy 版本文本的最大字符数。 */
    public static final int MAX_POLICY_VERSION_LENGTH = 32;
    /** 稳定结果代码的最大字符数。 */
    public static final int MAX_CODE_LENGTH = 128;
    /** 面向模型的单条结果消息最大字符数。 */
    public static final int MAX_MESSAGE_LENGTH = 1_024;
    /** 义务说明的最大字符数。 */
    public static final int MAX_DESCRIPTION_LENGTH = 2_048;
    /** 单条结论陈述的最大字符数。 */
    public static final int MAX_CLAIM_TEXT_LENGTH = 4_096;
    /** 单个控制集合的最大元素数。 */
    public static final int MAX_CONTROL_ITEMS = 128;
    /** 单次工具结果可返回的最大 Artifact 数。 */
    public static final int MAX_ARTIFACTS = 64;
    /** 单个结论候选的最大 Claim 数。 */
    public static final int MAX_CLAIMS = 64;
    /** SHA-256 十六进制文本的固定字符数。 */
    public static final int SHA256_HEX_LENGTH = 64;

    private CoordinationLimits() {
    }
}
