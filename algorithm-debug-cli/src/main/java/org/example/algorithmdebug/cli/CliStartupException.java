package org.example.algorithmdebug.cli;

/** CLI 默认运行时尚未建立时产生的结构化启动失败。 */
final class CliStartupException extends RuntimeException {
    private final String code;

    CliStartupException(String code, String message) {
        this(code, message, null);
    }

    CliStartupException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("CLI startup error code and message are required");
        }
        this.code = code;
    }

    String code() {
        return code;
    }
}

