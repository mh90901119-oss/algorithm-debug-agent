package org.example.algorithmdebug.staticanalysis;

/** 静态目录无法在安全边界内构建时抛出的结构化边界异常。 */
public final class StaticAnalysisException extends RuntimeException {

    /** 创建带原因的静态分析异常。 */
    public StaticAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 创建不带底层异常的静态分析异常。 */
    public StaticAnalysisException(String message) {
        super(message);
    }
}
