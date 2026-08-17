package org.example.algorithmdebug.casecore;

/** Agent Workspace 布局或持久化操作失败。 */
public final class WorkspaceException extends RuntimeException {

    /** 未细分的 Workspace 操作错误码。 */
    public static final String DEFAULT_CODE = "WORKSPACE_OPERATION_FAILED";

    private final String code;

    /**
     * 创建带说明的 Workspace 异常。
     *
     * @param message 面向调用方的失败说明
     */
    public WorkspaceException(String message) {
        this(DEFAULT_CODE, message, null);
    }

    /**
     * 创建保留底层原因的 Workspace 异常。
     *
     * @param message 面向调用方的失败说明
     * @param cause 文件系统或序列化层原始异常
     */
    public WorkspaceException(String message, Throwable cause) {
        this(DEFAULT_CODE, message, cause);
    }

    /**
     * 创建带稳定机器错误码的 Workspace 异常。
     *
     * @param code 稳定错误码
     * @param message 面向调用方的失败说明
     */
    public WorkspaceException(String code, String message) {
        this(code, message, null);
    }

    /**
     * 创建带稳定机器错误码并保留底层原因的 Workspace 异常。
     *
     * @param code 稳定错误码
     * @param message 面向调用方的失败说明
     * @param cause 底层原始异常
     */
    public WorkspaceException(String code, String message, Throwable cause) {
        super(requireMessage(message), cause);
        if (code == null || code.isBlank() || !code.equals(code.strip())) {
            throw new IllegalArgumentException("WorkspaceException code 不能为空或包含首尾空白");
        }
        this.code = code;
    }

    /** @return 稳定的机器可读错误码 */
    public String code() {
        return code;
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("WorkspaceException message 不能为空");
        }
        return message;
    }
}
