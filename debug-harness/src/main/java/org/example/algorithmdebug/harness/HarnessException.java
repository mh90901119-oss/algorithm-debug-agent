package org.example.algorithmdebug.harness;

/** Debug Harness 在输出发现、验证或复制阶段的结构化异常。 */
public final class HarnessException extends Exception {

    private final String code;

    /** 创建 Harness 异常。 */
    public HarnessException(String code, String message) {
        this(code, message, null);
    }

    /** 创建保留底层原因的 Harness 异常。 */
    public HarnessException(String code, String message, Throwable cause) {
        super(requireText(message, "message"), cause);
        this.code = requireText(code, "code");
    }

    /** @return 稳定机器错误码 */
    public String code() {
        return code;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
