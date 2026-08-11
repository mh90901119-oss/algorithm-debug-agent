package org.example.algorithmdebug.harness;

/** Surefire XML 读取或安全校验失败，保留底层 cause 供 Agent 单独报告。 */
public final class SurefireDiagnosticException extends Exception {
    public SurefireDiagnosticException(String message, Throwable cause) {
        super(message, cause);
    }
}
