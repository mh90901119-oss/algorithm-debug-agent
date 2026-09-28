package org.example.algorithmdebug.casecore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.example.algorithmdebug.contracts.coordination.CoordinationLimits;

/** 控制面追加归档共享的版本、预算、哈希和输入校验。 */
final class ControlArchiveSupport {
    static final String ARCHIVE_SCHEMA_VERSION = "1.0";
    static final int SHA256_HEX_LENGTH = 64;
    static final int MAX_PROVENANCE_REFERENCES = 128;
    static final int MAX_INVESTIGATION_EVENTS = 4_096;
    static final int MAX_REFERENCE_LENGTH = 1_024;

    static final String OPERATION_CONFLICT = "OPERATION_JOURNAL_CONFLICT";
    static final String INVESTIGATION_CONFLICT = "INVESTIGATION_JOURNAL_CONFLICT";
    static final String INVESTIGATION_IDENTITY_MISMATCH = "INVESTIGATION_IDENTITY_MISMATCH";
    static final String INVESTIGATION_LIMIT_EXCEEDED = "INVESTIGATION_LIMIT_EXCEEDED";
    static final String COORDINATION_DECISION_INVALID = "COORDINATION_DECISION_INVALID";

    private static final Pattern SHA256_PATTERN = Pattern.compile(
            "[0-9a-f]{" + SHA256_HEX_LENGTH + "}");

    private ControlArchiveSupport() {
    }

    static <T> T notNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    static String version(String value) {
        if (!ARCHIVE_SCHEMA_VERSION.equals(value)) {
            throw new IllegalArgumentException(
                    "Unsupported control archive schemaVersion: " + value);
        }
        return value;
    }

    static String sha256(String value, String field) {
        if (value == null || !SHA256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
        return value;
    }

    static String id(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > CoordinationLimits.MAX_ID_LENGTH
                || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException(field + " must be a safe opaque ID");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }

    static List<String> provenance(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_PROVENANCE_REFERENCES) {
            throw new IllegalArgumentException("provenance must contain one to "
                    + MAX_PROVENANCE_REFERENCES + " references");
        }
        List<String> copy = values.stream()
                .map(value -> text(value, "provenance", MAX_REFERENCE_LENGTH))
                .toList();
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("provenance must not contain duplicates");
        }
        return copy;
    }

    static String text(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String digest(byte[] content) {
        notNull(content, "content");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static WorkspaceException operationConflict(String message) {
        return new WorkspaceException(OPERATION_CONFLICT, message);
    }

    static WorkspaceException operationConflict(String message, Throwable cause) {
        return new WorkspaceException(OPERATION_CONFLICT, message, cause);
    }

    static WorkspaceException investigationConflict(String message) {
        return new WorkspaceException(INVESTIGATION_CONFLICT, message);
    }

    static WorkspaceException investigationConflict(String message, Throwable cause) {
        return new WorkspaceException(INVESTIGATION_CONFLICT, message, cause);
    }
}
