package org.example.algorithmdebug.staticanalysis;

/** 静态目录无法在安全边界内构建时抛出的结构化边界异常。 */
public final class StaticAnalysisException extends RuntimeException {
    public static final String DEFAULT_CODE = "STATIC_ANALYSIS_FAILED";

    private final String code;

    /** 创建带原因的静态分析异常。 */
    public StaticAnalysisException(String message, Throwable cause) {
        this(DEFAULT_CODE, message, cause);
    }

    /** 创建不带底层异常的静态分析异常。 */
    public StaticAnalysisException(String message) {
        this(DEFAULT_CODE, message, null);
    }

    /** 创建可由上层安全暴露的结构化静态分析失败。 */
    public StaticAnalysisException(String code, String message) {
        this(code, message, null);
    }

    private StaticAnalysisException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null");
        }
        this.code = code;
    }

    /** 返回不包含本机源码细节的稳定错误码。 */
    public String code() {
        return code;
    }
}
