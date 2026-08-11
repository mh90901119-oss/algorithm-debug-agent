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

    private ContractChecks() {
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " 不允许包含首尾空白");
        }
        return value;
    }

    static String requireOpaqueId(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (checked.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(fieldName + " 长度不能超过 " + MAX_ID_LENGTH);
        }
        if (checked.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " 不允许包含控制字符");
        }
        return checked;
    }

    static String requireJavaQualifiedName(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (!JAVA_QUALIFIED_NAME.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " 不是有效的 Java 全限定类名: " + checked);
        }
        return checked;
    }

    static String requireJavaMethodName(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (!JAVA_METHOD_NAME.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " 不是有效的 Java 方法名: " + checked);
        }
        return checked;
    }

    static String requireSha256(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (!SHA256.matcher(checked).matches()) {
            throw new IllegalArgumentException(fieldName + " 必须是 64 位十六进制 SHA-256");
        }
        return checked.toLowerCase(Locale.ROOT);
    }

    static String requirePortableRelativePath(String value, String fieldName) {
        String checked = requireNonBlank(value, fieldName);
        if (checked.startsWith("/") || checked.contains("\\") || checked.contains(":")) {
            throw new IllegalArgumentException(fieldName + " 必须是使用 / 分隔的可移植相对路径");
        }
        String[] segments = checked.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(fieldName + " 包含非法路径段: " + checked);
            }
        }
        return checked;
    }

    static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " 不能为空");
    }

    static <T> List<T> immutableList(List<T> values, String fieldName) {
        requireNonNull(values, fieldName);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " 不允许包含 null");
        }
        return List.copyOf(values);
    }

    static List<String> immutableNonBlankStrings(List<String> values, String fieldName) {
        List<String> copied = immutableList(values, fieldName);
        copied.forEach(value -> requireNonBlank(value, fieldName + " item"));
        return copied;
    }
}

