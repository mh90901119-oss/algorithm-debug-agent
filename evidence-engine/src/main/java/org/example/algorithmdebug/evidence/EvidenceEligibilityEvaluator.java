package org.example.algorithmdebug.evidence;

import java.util.ArrayList;
import java.util.List;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceEligibilityReason;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 根据确定性采集事实计算动态证据资格，不调用模型。 */
public final class EvidenceEligibilityEvaluator {

    /**
     * 计算正交资格。
     *
     * @param context 已完成校验的采集事实
     * @return 不可变资格结果
     */
    public EvidenceEligibility evaluate(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        boolean baselineRequired = context.targetFailed();
        boolean baselineComparable = baselineRequired
                && (context.baselineOutcome() == ComparisonOutcome.MATCHED
                || context.baselineOutcome() == ComparisonOutcome.CHANGED);
        boolean failureFingerprintMatched = baselineRequired
                && context.baselineOutcome() == ComparisonOutcome.MATCHED;
        boolean confirmationEligible = context.artifactReadable()
                && context.collectionComplete()
                && context.obligationSatisfied()
                && (!baselineRequired || failureFingerprintMatched);
        return new EvidenceEligibility(
                SchemaVersions.EVIDENCE_ELIGIBILITY,
                context.artifactReadable(), context.collectionComplete(),
                baselineRequired, baselineComparable, failureFingerprintMatched,
                context.obligationSatisfied(), confirmationEligible,
                reasons(context, baselineRequired));
    }

    private static List<String> reasons(Context context, boolean baselineRequired) {
        ArrayList<String> reasons = new ArrayList<>();
        if (!context.artifactReadable()) {
            reasons.add(EvidenceEligibilityReason.ARTIFACT_UNREADABLE.name());
        }
        if (!context.collectionComplete()) {
            reasons.add(EvidenceEligibilityReason.COLLECTION_INCOMPLETE.name());
        }
        if (!baselineRequired) {
            reasons.add(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name());
        } else {
            reasons.add(switch (context.baselineOutcome()) {
                case MATCHED -> EvidenceEligibilityReason.FAILURE_FINGERPRINT_MATCHED.name();
                case CHANGED -> EvidenceEligibilityReason.FAILURE_FINGERPRINT_CHANGED.name();
                case INCOMPARABLE, NOT_COMPARED ->
                        EvidenceEligibilityReason.FAILURE_FINGERPRINT_INCOMPARABLE.name();
            });
        }
        if (!context.obligationSatisfied()) {
            reasons.add(EvidenceEligibilityReason.OBLIGATION_UNSATISFIED.name());
        }
        return List.copyOf(reasons);
    }

    /**
     * 资格计算所需的最小确定性输入。
     *
     * @param artifactReadable 归一化和校验产物是否可读
     * @param collectionComplete Collector 是否正常完成且未截断
     * @param targetFailed 目标 UT 是否失败
     * @param baselineOutcome 失败指纹比较结果；成功场景使用 NOT_COMPARED
     * @param obligationSatisfied 计划要求的有效观测是否存在
     */
    public record Context(
            boolean artifactReadable,
            boolean collectionComplete,
            boolean targetFailed,
            ComparisonOutcome baselineOutcome,
            boolean obligationSatisfied) {
        /** 校验比较结果必填。 */
        public Context {
            if (baselineOutcome == null) {
                throw new IllegalArgumentException("baselineOutcome must not be null");
            }
        }
    }
}
