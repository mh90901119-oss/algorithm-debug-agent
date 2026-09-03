package org.example.algorithmdebug.contracts;

/** 顶层栈帧精确值路径的通用 JDWP 条件。 */
public record JdwpValueCondition(
        String valuePath,
        JdwpConditionOperator operator,
        JdwpScalarType expectedType,
        String expectedValue) {

    /** 校验值路径和标量字面量，不执行任何业务语义推断。 */
    public JdwpValueCondition {
        JdwpCaptureSpec.validateValuePath(valuePath);
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
