package org.example.algorithmdebug.core;

/** Case/Run 应用用例向 CLI 暴露的稳定错误，保留底层 Adapter、Harness 或归档 cause。 */
public final class CaseRunException extends RuntimeException {

    private final String code;

    /** 创建带稳定错误码的应用异常。 */
    public CaseRunException(String code, String message) {
        this(code, message, null);
    }

    /** 创建带稳定错误码并保留底层 cause 的应用异常。 */
    public CaseRunException(String code, String message, Throwable cause) {
        super(requireText(message, "message"), cause);
        this.code = requireText(code, "code");
    }

    /** @return 面向调用方的稳定错误码 */
    public String code() {
        return code;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.strip();
    }
}
