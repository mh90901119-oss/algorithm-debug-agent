package org.example.algorithmdebug.contracts.coordination;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Coordination 子包内部共享的轻量契约校验。 */
final class CoordinationContractChecks {
    private static final Pattern SHA256 = Pattern.compile(
            "[0-9a-fA-F]{" + CoordinationLimits.SHA256_HEX_LENGTH + "}");

    private CoordinationContractChecks() {
    }

    static void requireVersion(String actual, String expected, String contractName) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Unsupported " + contractName + " schemaVersion: " + actual);
        }
    }

    static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    static String requireId(String value, String fieldName) {
        return requireText(value, fieldName, CoordinationLimits.MAX_ID_LENGTH, false);
    }

    static String requireText(
            String value, String fieldName, int maximumLength, boolean allowEmpty) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    fieldName + " must not contain leading or trailing whitespace");
        }
        if (!allowEmpty && value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    fieldName + " length must not exceed " + maximumLength);
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
        return value;
    }

    static String requireSha256(String value, String fieldName) {
        String checked = requireText(
                value, fieldName, CoordinationLimits.SHA256_HEX_LENGTH, false);
        if (!SHA256.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a hexadecimal SHA-256");
        }
        return checked.toLowerCase(java.util.Locale.ROOT);
    }

    static <T> List<T> immutableUniqueList(
            List<T> values, String fieldName, int maximumSize) {
        requireNonNull(values, fieldName);
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed " + maximumSize + " entries");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " must not contain null");
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(fieldName + " must not contain duplicates");
        }
        return List.copyOf(values);
    }

    static List<String> immutableUniqueIds(List<String> values, String fieldName) {
        List<String> copied = immutableUniqueList(
                values, fieldName, CoordinationLimits.MAX_CONTROL_ITEMS);
        copied.forEach(value -> requireId(value, fieldName + " item"));
        return copied;
    }

    static <T> Optional<T> optional(Optional<T> value, String fieldName) {
        requireNonNull(value, fieldName);
        return value;
    }
}
