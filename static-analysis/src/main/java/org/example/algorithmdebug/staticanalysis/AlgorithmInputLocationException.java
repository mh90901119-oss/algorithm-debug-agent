package org.example.algorithmdebug.staticanalysis;

/** 算法输入无法按当前单输入、直接字面量边界确定时的结构化异常。 */
public final class AlgorithmInputLocationException extends RuntimeException {
    private final String code;

    /** 创建不暴露目标机器路径的定位异常。 */
    public AlgorithmInputLocationException(String code, String message) {
        super(message);
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Algorithm input error code and message are required");
        }
        this.code = code;
    }

    /** @return 稳定公开错误码 */
    public String code() {
        return code;
    }
}
