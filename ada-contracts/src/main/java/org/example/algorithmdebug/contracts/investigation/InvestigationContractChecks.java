package org.example.algorithmdebug.contracts.investigation;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Investigation 子包内部共享的不可变契约校验。 */
final class InvestigationContractChecks {
    private static final Pattern SHA256 = Pattern.compile(
            "[0-9a-fA-F]{" + InvestigationLimits.SHA256_HEX_LENGTH + "}");

    private InvestigationContractChecks() {
    }

    static void version(String actual, String expected, String name) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Unsupported " + name + " schemaVersion: " + actual);
        }
    }

    static <T> T notNull(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    static String id(String value, String field) {
        return text(value, field, InvestigationLimits.MAX_ID_LENGTH);
    }

    static String text(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must not have surrounding whitespace");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length " + maximumLength);
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }

    static String sha256(String value, String field) {
        String checked = text(value, field, InvestigationLimits.SHA256_HEX_LENGTH);
        if (!SHA256.matcher(checked).matches()) {
            throw new IllegalArgumentException(field + " must be a hexadecimal SHA-256");
        }
        return checked.toLowerCase(Locale.ROOT);
    }

    static <T> List<T> unique(List<T> values, String field, int maximumSize) {
        notNull(values, field);
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(field + " exceeds maximum size " + maximumSize);
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return List.copyOf(values);
    }

    static List<String> uniqueTexts(List<String> values, String field, int maximumSize) {
        List<String> copied = unique(values, field, maximumSize);
        copied.forEach(value -> text(value, field + " item", InvestigationLimits.MAX_TEXT_LENGTH));
        return copied;
    }

    static <T> Optional<T> optional(Optional<T> value, String field) {
        return notNull(value, field);
    }
}
