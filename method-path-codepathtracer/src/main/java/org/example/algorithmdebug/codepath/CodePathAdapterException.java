package org.example.algorithmdebug.codepath;

/** CodePath 原始数据或外部工具边界失败。 */
public final class CodePathAdapterException extends Exception {
    private final String code;

    /** 创建结构化 Adapter 异常。 */
    public CodePathAdapterException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return 稳定机器错误码 */
    public String code() {
        return code;
    }
}
