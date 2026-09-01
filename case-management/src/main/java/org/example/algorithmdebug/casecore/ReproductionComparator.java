package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.RunResultFingerprint;

import java.util.ArrayList;
import java.util.List;

/** 按固定 Gantt/目标失败维度比较两个 Run 指纹，不解释业务原因。 */
public final class ReproductionComparator {

    /** 比较发生在相同 Context 内，或当前 Context 与最近旧 Context 之间。 */
    public enum Scope {
        SAME_CONTEXT,
        CROSS_CONTEXT
    }

    /**
     * 比较参考和当前 Run 的目标观察。
     *
     * @param reference 已保存的不可变参考指纹
     * @param current 当前 Run 指纹
     * @param scope 比较范围
     * @return 固定模板的比较事实
     */
    public Result compare(
            RunResultFingerprint reference,
            RunResultFingerprint current,
            Scope scope) {
        if (reference == null || current == null || scope == null) {
            throw new IllegalArgumentException("Comparison arguments must not be null");
        }
        if (!reference.caseId().equals(current.caseId())) {
            throw new IllegalArgumentException("RunResultFingerprints from different Cases must not be compared");
        }

        List<String> changed = new ArrayList<>(1);
        if (!reference.targetFailureSha256().equals(current.targetFailureSha256())) {
            changed.add("TARGET_FAILURE");
        }
        ComparisonOutcome outcome = changed.isEmpty()
                ? ComparisonOutcome.MATCHED
                : ComparisonOutcome.CHANGED;
        String dimensions = changed.isEmpty() ? "NONE" : String.join(",", changed);
        String summary = "Baseline " + outcome
                + "; scope=" + scope
                + "; referenceRunId=" + reference.runId().value()
                + "; changedDimensions=" + dimensions;
        if (summary.length() > 2_048) {
            throw new IllegalStateException("Comparison summary exceeds the 2 KiB limit");
        }
        return new Result(outcome, summary, changed);
    }

    /** 确定性比较结果；变化维度顺序固定为 GANTT、TARGET_FAILURE。 */
    public record Result(
            ComparisonOutcome outcome,
            String summary,
            List<String> changedDimensions) {

        /** 防止调用方修改变化维度。 */
        public Result {
            if (outcome == null || summary == null || changedDimensions == null) {
                throw new IllegalArgumentException("Comparison result fields must not be null");
            }
            changedDimensions = List.copyOf(changedDimensions);
        }
    }
}
