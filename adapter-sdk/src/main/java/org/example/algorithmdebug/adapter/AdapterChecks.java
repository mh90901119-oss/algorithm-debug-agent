package org.example.algorithmdebug.adapter;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Adapter SDK 值对象共用的构造时校验。 */
final class AdapterChecks {

    private static final Pattern ADAPTER_ID = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern MAVEN_PROPERTY_KEY = Pattern.compile("[A-Za-z0-9_.-]+");

    private AdapterChecks() {
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " 不允许包含首尾空白");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " 不允许包含控制字符");
        }
        return value;
    }

    static String requireAdapterId(String value) {
        String checked = requireNonBlank(value, "adapterId");
        if (!ADAPTER_ID.matcher(checked).matches()) {
            throw new IllegalArgumentException("adapterId 必须是 3 到 64 位小写字母、数字或连字符");
        }
        return checked;
    }

    static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " 不能为空");
    }

    static <T> Set<T> immutableSet(Set<T> values, String fieldName) {
        requireNonNull(values, fieldName);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " 不允许包含 null");
        }
        return Set.copyOf(values);
    }

    static List<String> immutableTokens(List<String> values, String fieldName, boolean rejectWhitespace) {
        requireNonNull(values, fieldName);
        for (String value : values) {
            String checked = requireNonBlank(value, fieldName + " item");
            if (rejectWhitespace && checked.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(fieldName + " 的单个参数不允许包含空白: " + checked);
            }
        }
        return List.copyOf(values);
    }

    static Map<String, String> immutableMavenProperties(Map<String, String> properties) {
        requireNonNull(properties, "mavenProperties");
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            String checkedKey = requireNonBlank(key, "mavenProperties key");
            if (!MAVEN_PROPERTY_KEY.matcher(checkedKey).matches()) {
                throw new IllegalArgumentException("非法 Maven property key: " + checkedKey);
            }
            copied.put(checkedKey, requireNonNull(value, "mavenProperties value"));
        });
        return Collections.unmodifiableMap(copied);
    }

    static Duration requirePositiveDuration(Duration duration, String fieldName) {
        requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " 必须大于零");
        }
        return duration;
    }
}

