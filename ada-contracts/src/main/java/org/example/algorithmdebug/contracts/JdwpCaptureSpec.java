package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * JDWP Collector 的精确栈帧值投影。
 *
 * @param valuePaths 从顶层局部变量或 {@code this} 开始的精确字段路径，例如 {@code request.wafer.id}
 */
public record JdwpCaptureSpec(
        boolean stack,
        int maxFrames,
        int maxStringLength,
        List<String> valuePaths) {

    public JdwpCaptureSpec {
        valuePaths = valuePaths == null ? List.of() : List.copyOf(valuePaths);
        if (!stack && valuePaths.isEmpty()) {
            throw new IllegalArgumentException("JDWP capture must request stack or value paths");
        }
        if (maxFrames < 1 || maxFrames > 64
                || maxStringLength < 16 || maxStringLength > 1_024) {
            throw new IllegalArgumentException("JDWP capture exceeds the safety limits");
        }
        if (valuePaths.size() > 128) {
            throw new IllegalArgumentException("JDWP value paths exceed the safety limit");
        }
        valuePaths.forEach(JdwpCaptureSpec::validateValuePath);
        if (valuePaths.stream().distinct().count() != valuePaths.size()) {
            throw new IllegalArgumentException("JDWP value paths must not contain duplicates");
        }
    }

    public static JdwpCaptureSpec stackOnly() {
        return new JdwpCaptureSpec(true, 8, 256, List.of());
    }

    static void validateValuePath(String valuePath) {
        if (valuePath == null || valuePath.isBlank() || valuePath.length() > 2_048) {
            throw new IllegalArgumentException("JDWP value path is invalid");
        }
        String[] segments = valuePath.split("\\.", -1);
        if (segments.length > 8) {
            throw new IllegalArgumentException("JDWP value path exceeds 8 segments");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || !isAsciiIdentifierStart(segment.charAt(0))) {
                throw new IllegalArgumentException("JDWP value path contains an invalid segment");
            }
            for (int index = 1; index < segment.length(); index++) {
                if (!isAsciiIdentifierPart(segment.charAt(index))) {
                    throw new IllegalArgumentException("JDWP value path contains an invalid segment");
                }
            }
        }
    }

    private static boolean isAsciiIdentifierStart(char value) {
        return value == '_' || value == '$'
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z';
    }

    private static boolean isAsciiIdentifierPart(char value) {
        return isAsciiIdentifierStart(value) || value >= '0' && value <= '9';
    }
}
