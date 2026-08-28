package org.example.algorithmdebug.casecore.logging;

import java.util.regex.Pattern;

/** 对日志动态值执行路径、凭据、控制字符和非 ASCII 清理。 */
public final class SensitiveLogSanitizer {
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)(?:[a-z]:[\\\\/])[^\\s\\\"']+");
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_.-])/(?:[^\\s\\\"']+/)+[^\\s\\\"']*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(token|password|secret|authorization|api[-_]?key)\\s*[=:]\\s*[^\\s,;]+");

    /** 清理单行字段值。 */
    public String singleLine(String value) {
        return ascii(redact(value == null ? "" : value))
                .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 清理异常堆栈并保留换行和 cause 链。 */
    public String multiline(String value) {
        String normalized = ascii(redact(value == null ? "" : value))
                .replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        return normalized;
    }

    private static String redact(String value) {
        String redacted = SECRET.matcher(value).replaceAll("$1=<redacted>");
        redacted = WINDOWS_PATH.matcher(redacted).replaceAll("<redacted-path>");
        return UNIX_PATH.matcher(redacted).replaceAll("<redacted-path>");
    }

    private static String ascii(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t'
                    || (codePoint >= 32 && codePoint <= 126)) {
                result.appendCodePoint(codePoint);
            } else {
                result.append('?');
            }
        });
        return result.toString();
    }
}
