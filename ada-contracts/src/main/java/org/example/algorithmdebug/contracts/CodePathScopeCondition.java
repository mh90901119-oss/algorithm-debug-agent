package org.example.algorithmdebug.contracts;

import java.math.BigDecimal;

/**
 * 对一次 CodePath 作用域调用的参数投影执行精确相等判断。
 *
 * @param projectionName 作用域方法中已声明的参数投影名
 * @param expectedType 预期值类型
 * @param expectedValue 预期标量；类型为 {@link CodePathScalarType#NULL} 时必须为空
 */
public record CodePathScopeCondition(
        String projectionName,
        CodePathScalarType expectedType,
        Object expectedValue) {

    public CodePathScopeCondition {
        projectionName = ContractChecks.requireBoundedText(
                projectionName, "projectionName", 128, false);
        expectedType = ContractChecks.requireNonNull(expectedType, "expectedType");
        validateValue(expectedType, expectedValue);
    }

    private static void validateValue(CodePathScalarType type, Object value) {
        boolean valid = switch (type) {
            case STRING -> value instanceof String text && text.length() <= 1_024;
            case INTEGER -> isInteger(value);
            case DECIMAL -> isFiniteNumber(value);
            case BOOLEAN -> value instanceof Boolean;
            case NULL -> value == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("expectedValue does not match expectedType");
        }
    }

    private static boolean isInteger(Object value) {
        if (!(value instanceof Number number) || !isFiniteNumber(number)) return false;
        return new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
    }

    private static boolean isFiniteNumber(Object value) {
        if (!(value instanceof Number number)) return false;
        if (number instanceof Double decimal) return Double.isFinite(decimal);
        if (number instanceof Float decimal) return Float.isFinite(decimal);
        try {
            new BigDecimal(number.toString());
            return true;
        } catch (NumberFormatException failure) {
            return false;
        }
    }
}
