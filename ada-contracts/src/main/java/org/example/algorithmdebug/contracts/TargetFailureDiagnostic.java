package org.example.algorithmdebug.contracts;

/**
 * 从目标构建或测试产物中确定性提取的通用失败事实。
 * 字段可以为空字符串表示源产物未提供；本类型不推断算法业务根因。
 */
public record TargetFailureDiagnostic(
        FailureCategory category,
        String exceptionClass,
        String normalizedMessage,
        String cause,
        String stableBusinessFrame) {

    /** 校验分类和有界文本，保留源产物缺失字段为空字符串的语义。 */
    public TargetFailureDiagnostic {
        category = ContractChecks.requireNonNull(category, "category");
        exceptionClass = checkedText(exceptionClass, "exceptionClass");
        normalizedMessage = checkedText(normalizedMessage, "normalizedMessage");
        cause = checkedText(cause, "cause");
        stableBusinessFrame = checkedText(stableBusinessFrame, "stableBusinessFrame");
    }

    private static String checkedText(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为 null");
        }
        String normalized = value.strip();
        if (normalized.length() > 8192) {
            throw new IllegalArgumentException(field + " 长度不能超过 8192");
        }
        return normalized;
    }
}
