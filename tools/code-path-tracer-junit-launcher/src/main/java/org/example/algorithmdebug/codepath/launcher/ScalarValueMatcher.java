package org.example.algorithmdebug.codepath.launcher;

import java.math.BigDecimal;

/** Scope 条件使用的精确标量相等判断。 */
final class ScalarValueMatcher {
    private ScalarValueMatcher() {}

    static boolean matches(
            LauncherCodePathPlan.ScopeCondition condition, ProjectionValue actual) {
        if (condition.expectedType() == LauncherCodePathPlan.ScalarType.NULL) {
            return actual.status() == ProjectionStatus.NULL;
        }
        if (actual.status() != ProjectionStatus.VALUE) return false;
        Object value = actual.value();
        return switch (condition.expectedType()) {
            case STRING -> value instanceof String && value.equals(condition.expectedValue());
            case INTEGER -> value instanceof Number && condition.expectedValue() instanceof Number
                    && decimal(value).stripTrailingZeros().scale() <= 0
                    && decimal(value).compareTo(decimal(condition.expectedValue())) == 0;
            case DECIMAL -> value instanceof Number && condition.expectedValue() instanceof Number
                    && decimal(value).compareTo(decimal(condition.expectedValue())) == 0;
            case BOOLEAN -> value instanceof Boolean && value.equals(condition.expectedValue());
            case NULL -> false;
        };
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }
}
