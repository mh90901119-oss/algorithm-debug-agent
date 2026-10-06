package org.example.algorithmdebug.runtime;

/** Runtime 组合失败的结构化异常，保留稳定 code 与底层 cause。 */
public final class RuntimeBootstrapException extends RuntimeException {
    private final String code;

    /** 创建组合异常。 */
    public RuntimeBootstrapException(String code, String message) {
        this(code, message, null);
    }

    /** 创建带 cause 的组合异常。 */
    public RuntimeBootstrapException(String code, String message, Throwable cause) {
        super(requireText(message, "message"), cause);
        this.code = requireText(code, "code");
    }

    /** @return 稳定错误码 */
    public String code() {
        return code;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 256) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
