package org.example.algorithmdebug.jdwp;

/** JDWP 适配器在端口、命令或双进程协调边界产生的结构化失败。 */
public final class JdwpAdapterException extends Exception {
    private final String code;
    private final boolean targetStarted;
    private final boolean collectorStarted;

    /**
     * 创建尚未启动双进程的结构化失败。
     *
     * @param code 稳定机器错误码
     * @param message 人类可读摘要
     * @param cause 原始原因；无底层异常时可以为 null
     */
    public JdwpAdapterException(String code, String message, Throwable cause) {
        this(code, message, cause, false, false);
    }

    /**
     * 创建同时记录双进程启动事实的结构化失败。
     *
     * @param code 稳定机器错误码
     * @param message 人类可读摘要
     * @param cause 原始原因；无底层异常时可以为 null
     * @param targetStarted 目标 Maven/UT 是否已启动
     * @param collectorStarted Collector 是否已启动
     */
    public JdwpAdapterException(
            String code,
            String message,
            Throwable cause,
            boolean targetStarted,
            boolean collectorStarted) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        if (collectorStarted && !targetStarted) {
            throw new IllegalArgumentException("Collector 已启动时目标必须已经启动");
        }
        this.code = code;
        this.targetStarted = targetStarted;
        this.collectorStarted = collectorStarted;
    }

    /** @return 稳定机器错误码 */
    public String code() {
        return code;
    }

    /** @return 失败前目标 Maven/UT 进程是否已经成功启动 */
    public boolean targetStarted() {
        return targetStarted;
    }

    /** @return 失败前 Collector 进程是否已经成功启动 */
    public boolean collectorStarted() {
        return collectorStarted;
    }
}
