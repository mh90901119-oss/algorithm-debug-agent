package org.example.algorithmdebug.methodpath;

/** Collector 的结构化失败；保留稳定错误码和底层 cause。 */
public final class MethodPathCollectionException extends Exception {
    private final String code;
    private final boolean processStarted;
    private final int exitCode;

    /** 创建带 cause 的 Collector 错误。 */
    public MethodPathCollectionException(String code, String message, Throwable cause) {
        this(code, message, cause, false, -1);
    }

    /** 创建携带进程是否已启动和退出码事实的 Collector 错误。 */
    public MethodPathCollectionException(
            String code, String message, Throwable cause, boolean processStarted, int exitCode) {
        super(message, cause);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("code 非法");
        }
        this.code = code;
        if (!processStarted && exitCode != -1) {
            throw new IllegalArgumentException("未启动进程时 exitCode 必须为 -1");
        }
        this.processStarted = processStarted;
        this.exitCode = exitCode;
    }

    /** @return 稳定机器错误码 */
    public String code() {
        return code;
    }

    /** @return Collector 子进程是否已经成功启动 */
    public boolean processStarted() {
        return processStarted;
    }

    /** @return 退出码；未知或未启动为 -1 */
    public int exitCode() {
        return exitCode;
    }
}
