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
            throw new IllegalArgumentException("比较参数不能为空");
        }
        if (!reference.caseId().equals(current.caseId())) {
            throw new IllegalArgumentException("不同 Case 的 RunResultFingerprint 不得比较");
        }

        List<String> changed = new ArrayList<>(2);
        if (!reference.ganttNormalizedJsonSha256()
                .equals(current.ganttNormalizedJsonSha256())) {
            changed.add("GANTT");
        }
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
            throw new IllegalStateException("比较摘要超过 2 KiB 上限");
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
                throw new IllegalArgumentException("比较结果字段不能为空");
            }
            changedDimensions = List.copyOf(changedDimensions);
        }
    }
}
