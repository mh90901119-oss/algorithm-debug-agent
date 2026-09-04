package org.example.algorithmdebug.casecore;

import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.RunResultFingerprint;

/** 比较同一 Case 和 Analysis 内的普通 Run 与动态 Run 目标失败指纹。 */
public final class ReproductionComparator {

    /**
     * 比较参考和当前 Run 的目标失败观察。
     *
     * @param reference 同 Analysis 的普通 Run 指纹
     * @param current 当前动态 Run 指纹
     * @return 固定模板的比较事实
     */
    public Result compare(
            RunResultFingerprint reference,
            RunResultFingerprint current) {
        if (reference == null || current == null) {
            throw new IllegalArgumentException("Comparison arguments must not be null");
        }
        if (!reference.caseId().equals(current.caseId())
                || !reference.analysisId().equals(current.analysisId())) {
            throw new IllegalArgumentException(
                    "RunResultFingerprints from different Cases or Analyses must not be compared");
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
                + "; scope=SAME_ANALYSIS"
                + "; referenceRunId=" + reference.runId().value()
                + "; changedDimensions=" + dimensions;
        if (summary.length() > 2_048) {
            throw new IllegalStateException("Comparison summary exceeds the 2 KiB limit");
        }
        return new Result(outcome, summary, changed);
    }

    /** 确定性比较结果。 */
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