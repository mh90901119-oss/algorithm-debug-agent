package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 契约对象共用的轻量校验方法。
 *
 * <p>这里不引入 Bean Validation，确保 contracts 模块保持轻量且构造后的对象始终满足基本不变量。</p>
 */
final class ContractChecks {

    private static final int MAX_ID_LENGTH = 128;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern JAVA_QUALIFIED_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern JAVA_METHOD_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern JAVA_PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern JVM_INTERNAL_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*");

    private ContractChecks() {
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " must not contain leading or trailing whitespace");
        }
        return value;
    }

    static String requireOpaqueId(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (checked.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(fieldName + " length must not exceed " + MAX_ID_LENGTH);
        }
        if (checked.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
        return checked;
    }

    static String requireJavaQualifiedName(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (!JAVA_QUALIFIED_NAME.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a valid fully qualified Java class name: " + checked);
        }
        return checked;
    }

    static String requireJavaMethodName(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (!JAVA_METHOD_NAME.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a valid Java method name: " + checked);
        }
        return checked;
    }

    static String requireJavaPackageName(String value, String fieldName) {
        String checked = requireBoundedText(value, fieldName, 512, false);
        if (!JAVA_PACKAGE_NAME.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a valid Java package name: " + checked);
        }
        return checked;
    }

    static String requireJavaExecutableName(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (!"<init>".equals(checked) && !JAVA_METHOD_NAME.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a valid Java executable member name: " + checked);
        }
        return checked;
    }

    static String requireJvmMethodDescriptor(
            String value, String fieldName, String executableName) {
        String checked = requireBoundedText(value, fieldName, 512, false);
        int cursor = 0;
        if (checked.charAt(cursor++) != '(') {
            throw invalidDescriptor(fieldName);
        }
        while (cursor < checked.length() && checked.charAt(cursor) != ')') {
            cursor = parseFieldDescriptor(checked, cursor, fieldName);
        }
        if (cursor >= checked.length() || checked.charAt(cursor++) != ')' || cursor >= checked.length()) {
            throw invalidDescriptor(fieldName);
        }
        boolean returnsVoid = checked.charAt(cursor) == 'V';
        cursor = returnsVoid ? cursor + 1 : parseFieldDescriptor(checked, cursor, fieldName);
        if (cursor != checked.length() || ("<init>".equals(executableName) && !returnsVoid)) {
            throw invalidDescriptor(fieldName);
        }
        return checked;
    }

    private static int parseFieldDescriptor(String value, int start, String fieldName) {
        if (start >= value.length()) {
            throw invalidDescriptor(fieldName);
        }
        int cursor = start;
        int dimensions = 0;
        while (cursor < value.length() && value.charAt(cursor) == '[') {
            dimensions++;
            cursor++;
        }
        if (dimensions > 255 || cursor >= value.length()) {
            throw invalidDescriptor(fieldName);
        }
        char kind = value.charAt(cursor);
        if ("BCDFIJSZ".indexOf(kind) >= 0) {
            return cursor + 1;
        }
        if (kind != 'L') {
            throw invalidDescriptor(fieldName);
        }
        int terminator = value.indexOf(';', cursor + 1);
        if (terminator < 0 || terminator == cursor + 1) {
            throw invalidDescriptor(fieldName);
        }
        String internalName = value.substring(cursor + 1, terminator);
        if (!JVM_INTERNAL_NAME.matcher(internalName).matches()) {
            throw invalidDescriptor(fieldName);
        }
        return terminator + 1;
    }

    private static IllegalArgumentException invalidDescriptor(String fieldName) {
        return new IllegalArgumentException(fieldName + " is not a valid JVM method descriptor");
    }

    static String requireSha256(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (!SHA256.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a 64-character hexadecimal SHA-256");
        }
        return checked.toLowerCase(Locale.ROOT);
    }

    static String requirePortableRelativePath(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (checked.startsWith("/") || checked.contains("\\") || checked.contains(":")) {
            throw new IllegalArgumentException(fieldName + " must be a portable relative path separated by /");
        }
        String[] segments = checked.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(fieldName + " contains an invalid path segment: " + checked);
            }
        }
        return checked;
    }

    static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    static <T> List<T> immutableList(List<T> values, String fieldName) {
        requireNonNull(values, fieldName);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " must not contain null");
        }
        return List.copyOf(values);
    }

    static List<String> immutableNonBlankStrings(List<String> values, String fieldName) {
        List<String> copied = immutableList(values, fieldName);
        copied.forEach(value -> requireNonBlank(value, fieldName + " item"));
        return copied;
    }

    static String requireBoundedText(
            String value, String fieldName, int maximumLength, boolean allowEmpty) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        String checked = value.strip();
        if (!allowEmpty && checked.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " length must not exceed " + maximumLength);
        }
        return checked;
    }

    static List<String> immutableBoundedStrings(
            List<String> values, String fieldName, int maximumItemLength) {
        List<String> copied = immutableList(values, fieldName);
        copied.forEach(value -> requireBoundedText(
                value, fieldName + " item", maximumItemLength, false));
        return copied;
    }
}
