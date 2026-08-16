package org.example.algorithmdebug.casecore;

/** Agent Workspace 布局或持久化操作失败。 */
public final class WorkspaceException extends RuntimeException {

    /**
     * 创建带说明的 Workspace 异常。
     *
     * @param message 面向调用方的失败说明
     */
    public WorkspaceException(String message) {
        super(message);
    }

    /**
     * 创建保留底层原因的 Workspace 异常。
     *
     * @param message 面向调用方的失败说明
     * @param cause 文件系统或序列化层原始异常
     */
    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
