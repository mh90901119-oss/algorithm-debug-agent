package org.example.algorithmdebug.normalizer;

/** P4 归一化阶段的结构化失败，保留错误代码、JSONL 行号和原始 cause。 */
public final class NormalizationException extends RuntimeException {

    private final String code;
    private final long jsonlLine;

    /**
     * @param code 稳定错误代码
     * @param message 有界诊断说明
     * @param jsonlLine 从 1 开始的行号；文件级错误为 0
     * @param cause 原始失败；没有时为 {@code null}
     */
    public NormalizationException(String code, String message, long jsonlLine, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank() || message == null || message.isBlank()
                || jsonlLine < 0) {
            throw new IllegalArgumentException("归一化错误参数非法");
        }
        this.code = code;
        this.jsonlLine = jsonlLine;
    }

    /** @return 稳定错误代码 */
    public String code() {
        return code;
    }

    /** @return JSONL 行号；文件级错误为 0 */
    public long jsonlLine() {
        return jsonlLine;
    }
}
