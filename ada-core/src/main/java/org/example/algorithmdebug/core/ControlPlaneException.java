package org.example.algorithmdebug.core;

/** Core 对 CLI 暴露的稳定控制面领域异常，不泄漏 Case 实现类型。 */
public final class ControlPlaneException extends RuntimeException {

    private final String code;

    /**
     * 创建保留底层领域原因的 Core 异常。
     *
     * @param code 稳定机器错误码
     * @param message 内部诊断说明
     * @param cause Case 领域原始异常
     */
    public ControlPlaneException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null");
        }
        this.code = code;
    }

    /** @return 稳定机器错误码 */
    public String code() {
        return code;
    }
}
