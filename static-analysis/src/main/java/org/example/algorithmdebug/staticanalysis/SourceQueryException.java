package org.example.algorithmdebug.staticanalysis;

import org.example.algorithmdebug.contracts.investigation.SourceQueryErrorCode;

/** Source Query 的结构化失败；保留稳定错误码和底层 cause。 */
public final class SourceQueryException extends RuntimeException {
    private final SourceQueryErrorCode code;

    /** 创建不带底层 cause 的查询失败。 */
    public SourceQueryException(SourceQueryErrorCode code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    /** 创建保留底层 cause 的查询失败。 */
    public SourceQueryException(SourceQueryErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    /** @return 可跨适配器稳定映射的错误码 */
    public SourceQueryErrorCode code() {
        return code;
    }

    private static SourceQueryErrorCode requireCode(SourceQueryErrorCode code) {
        if (code == null) {
            throw new IllegalArgumentException("SourceQueryException code must not be null");
        }
        return code;
    }
}
