package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 顶层栈帧值路径的通用 JDWP 条件。
 *
 * <p>只允许读取局部变量（或 {@code this}）并沿实例字段前进，不表达 getter、方法调用、
 * 数组索引或集合遍历，避免把业务语义写入 Collector。</p>
 */
public record JdwpValueCondition(
        String localName,
        List<String> fieldPath,
        JdwpConditionOperator operator,
        JdwpScalarType expectedType,
        String expectedValue) {

    private static final Pattern JAVA_IDENTIFIER =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** 校验路径深度、Java 标识符和标量字面量。 */
    public JdwpValueCondition {
        localName = ContractChecks.requireBoundedText(localName, "localName", 256, false);
        if (!("this".equals(localName) || JAVA_IDENTIFIER.matcher(localName).matches())) {
            throw new IllegalArgumentException("localName must be a Java identifier or this");
        }
        fieldPath = fieldPath == null ? List.of() : List.copyOf(fieldPath);
        if (fieldPath.size() > 8 || fieldPath.stream().anyMatch(segment -> segment == null
                || !JAVA_IDENTIFIER.matcher(segment).matches())) {
            throw new IllegalArgumentException(
                    "fieldPath must contain at most 8 Java field identifiers");
        }
        operator = ContractChecks.requireNonNull(operator, "operator");
        expectedType = ContractChecks.requireNonNull(expectedType, "expectedType");
        if (expectedType == JdwpScalarType.NULL) {
            if (expectedValue != null && !expectedValue.isBlank()) {
                throw new IllegalArgumentException("NULL condition must not have expectedValue");
            }
            expectedValue = null;
        } else {
            expectedValue = ContractChecks.requireBoundedText(
                    expectedValue, "expectedValue", 1_024, false);
            validateLiteral(expectedType, expectedValue);
        }
    }

    private static void validateLiteral(JdwpScalarType type, String value) {
        try {
            switch (type) {
                case LONG -> Long.parseLong(value);
                case DOUBLE -> Double.parseDouble(value);
                case BOOLEAN -> {
                    if (!("true".equals(value) || "false".equals(value))) {
                        throw new IllegalArgumentException(
                                "BOOLEAN expectedValue must be true or false");
                    }
                }
                case CHAR -> {
                    if (value.codePointCount(0, value.length()) != 1) {
                        throw new IllegalArgumentException(
                                "CHAR expectedValue must contain one character");
                    }
                }
                case STRING, ENUM, NULL -> {
                    // Text and enum names need only the shared bounded-text validation.
                }
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    type + " expectedValue is not a valid scalar", failure);
        }
    }
}
