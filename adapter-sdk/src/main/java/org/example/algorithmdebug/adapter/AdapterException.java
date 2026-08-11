package org.example.algorithmdebug.adapter;

/**
 * Adapter 检查、定位、解析或语义哈希过程中的可预期失败。
 *
 * <p>错误码供 Agent 决定降级或下一动作；cause 保留底层文件、解析或算法异常。</p>
 */
public final class AdapterException extends Exception {

    private final String code;

    /**
     * 创建不带底层 cause 的 Adapter 异常。
     *
     * @param code 稳定错误码
     * @param message 错误说明
     */
    public AdapterException(String code, String message) {
        this(code, message, null);
    }

    /**
     * 创建保留底层 cause 的 Adapter 异常。
     *
     * @param code 稳定错误码
     * @param message 错误说明
     * @param cause 底层异常，可为空
     */
    public AdapterException(String code, String message, Throwable cause) {
        super(AdapterChecks.requireNonBlank(message, "message"), cause);
        this.code = AdapterChecks.requireNonBlank(code, "code");
    }

    /**
     * 返回机器可读的稳定错误码。
     *
     * @return Adapter 错误码
     */
    public String code() {
        return code;
    }
}

