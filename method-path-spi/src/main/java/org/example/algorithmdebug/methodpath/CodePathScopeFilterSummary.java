package org.example.algorithmdebug.methodpath;

/** CodePath 运行时范围条件的确定性覆盖统计。 */
public record CodePathScopeFilterSummary(
        boolean enabled,
        long observedInvocations,
        long matchedInvocations,
        long capturedInvocations,
        long skippedByWindowInvocations,
        long unavailableConditionInvocations) {

    /** 校验计数关系，避免下游把无效统计解释为业务事实。 */
    public CodePathScopeFilterSummary {
        if (observedInvocations < 0 || matchedInvocations < 0
                || unavailableConditionInvocations < 0
                || capturedInvocations < 0 || skippedByWindowInvocations < 0
                || capturedInvocations + skippedByWindowInvocations > matchedInvocations
                || matchedInvocations + unavailableConditionInvocations > observedInvocations) {
            throw new IllegalArgumentException("CodePath scope filter counts are invalid");
        }
        if (!enabled && (observedInvocations != 0 || matchedInvocations != 0
                || capturedInvocations != 0 || skippedByWindowInvocations != 0
                || unavailableConditionInvocations != 0)) {
            throw new IllegalArgumentException("Disabled CodePath scope filter must have zero counts");
        }
    }

    /** @return 未启用运行时范围过滤时使用的明确零值。 */
    public static CodePathScopeFilterSummary disabled() {
        return new CodePathScopeFilterSummary(false, 0, 0, 0, 0, 0);
    }

    /** 兼容未区分窗口内外匹配次数的历史调用方。 */
    public CodePathScopeFilterSummary(
            boolean enabled,
            long observedInvocations,
            long matchedInvocations,
            long unavailableConditionInvocations) {
        this(enabled, observedInvocations, matchedInvocations,
                matchedInvocations, 0, unavailableConditionInvocations);
    }
}
