package org.example.algorithmdebug.mcp.input;

import java.util.List;
import java.util.Optional;

/** MCP typed 输入共用的空值和可选文本校验。 */
final class McpInputChecks {
    private McpInputChecks() {
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    static <T> List<T> list(List<T> value, String field) {
        if (value == null || value.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null values");
        }
        return List.copyOf(value);
    }

    static Optional<String> optionalText(String value, String field, int maximumLength) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isBlank() || !value.equals(value.strip()) || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return Optional.of(value);
    }
}
