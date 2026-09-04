package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.ArtifactReference;

import java.util.List;

/** Case/Run 应用用例向 CLI 暴露的稳定错误，保留底层 Adapter、Harness 或归档 cause。 */
public final class CaseRunException extends RuntimeException {

    private final String code;
    private final List<ArtifactReference> artifacts;

    /** 创建带稳定错误码的应用异常。 */
    public CaseRunException(String code, String message) {
        this(code, message, null, List.of());
    }

    /** 创建带稳定错误码并保留底层 cause 的应用异常。 */
    public CaseRunException(String code, String message, Throwable cause) {
        this(code, message, cause, List.of());
    }

    /** 创建同时携带已安全归档失败产物的应用异常。 */
    public CaseRunException(
            String code,
            String message,
            Throwable cause,
            List<ArtifactReference> artifacts) {
        super(requireText(message, "message"), cause);
        this.code = requireText(code, "code");
        this.artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
    }

    /** @return 面向调用方的稳定错误码 */
    public String code() {
        return code;
    }

    /** @return 可供调用方读取的有界失败产物引用。 */
    public List<ArtifactReference> artifacts() {
        return artifacts;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value.strip();
    }
}
