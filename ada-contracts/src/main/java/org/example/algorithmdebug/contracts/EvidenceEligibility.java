package org.example.algorithmdebug.contracts;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 动态采集证据的正交资格判定。
 *
 * <p>该契约分别表达产物可读性、采集完整性、失败基线要求和计划义务是否满足，
 * 避免由单个布尔值同时承载“数据能否阅读”和“能否确认结论”两种语义。</p>
 *
 * @param schemaVersion 契约版本
 * @param artifactReadable 归档产物是否存在、完整且可解析
 * @param collectionComplete 采集是否正常完成且未截断
 * @param baselineRequired 当前目标结果是否要求失败复现基准
 * @param baselineComparable 所需失败基准是否可比较
 * @param failureFingerprintMatched 是否复现了同类失败
 * @param obligationSatisfied 本次采集义务是否得到有效观测
 * @param confirmationEligible 是否允许支持确认性结论
 * @param reasonCodes 确定性原因码
 */
public record EvidenceEligibility(
        String schemaVersion,
        boolean artifactReadable,
        boolean collectionComplete,
        boolean baselineRequired,
        boolean baselineComparable,
        boolean failureFingerprintMatched,
        boolean obligationSatisfied,
        boolean confirmationEligible,
        List<String> reasonCodes) {

    private static final int MAX_REASON_CODES = 16;
    private static final int MAX_REASON_CODE_LENGTH = 128;

    /** 校验版本、原因码和确认资格公式，拒绝内部自相矛盾的资格对象。 */
    public EvidenceEligibility {
        if (!SchemaVersions.EVIDENCE_ELIGIBILITY.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported EvidenceEligibility schemaVersion");
        }
        reasonCodes = ContractChecks.immutableBoundedStrings(
                reasonCodes, "reasonCodes", MAX_REASON_CODE_LENGTH);
        if (reasonCodes.size() > MAX_REASON_CODES) {
            throw new IllegalArgumentException("reasonCodes exceeds the limit");
        }
        if (new HashSet<>(reasonCodes).size() != reasonCodes.size()) {
            throw new IllegalArgumentException("reasonCodes must not contain duplicates");
        }
        reasonCodes.forEach(EvidenceEligibility::requireKnownReason);
        if (baselineComparable && !baselineRequired) {
            throw new IllegalArgumentException(
                    "A comparable baseline requires baselineRequired");
        }
        if (failureFingerprintMatched && (!baselineRequired || !baselineComparable)) {
            throw new IllegalArgumentException(
                    "A matched failure fingerprint requires a comparable required baseline");
        }
        validateReasonConsistency(
                artifactReadable, collectionComplete, baselineRequired,
                baselineComparable, failureFingerprintMatched, obligationSatisfied,
                reasonCodes);
        boolean expectedConfirmation = artifactReadable
                && collectionComplete
                && obligationSatisfied
                && (!baselineRequired || failureFingerprintMatched);
        if (confirmationEligible != expectedConfirmation) {
            throw new IllegalArgumentException(
                    "confirmationEligible does not match the eligibility dimensions");
        }
    }

    /** 为缺少资格字段的 v2 摘要生成保守的未知状态，不继承旧布尔值。 */
    public static EvidenceEligibility legacyUnknown() {
        return new EvidenceEligibility(
                SchemaVersions.EVIDENCE_ELIGIBILITY,
                false, false, false, false, false, false, false,
                List.of(EvidenceEligibilityReason.LEGACY_UNKNOWN.name()));
    }

    private static void requireKnownReason(String reasonCode) {
        try {
            EvidenceEligibilityReason.valueOf(reasonCode);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Unknown EvidenceEligibility reason code: " + reasonCode, failure);
        }
    }

    private static void validateReasonConsistency(
            boolean artifactReadable,
            boolean collectionComplete,
            boolean baselineRequired,
            boolean baselineComparable,
            boolean failureFingerprintMatched,
            boolean obligationSatisfied,
            List<String> reasonCodes) {
        Set<String> actual = Set.copyOf(reasonCodes);
        String legacyUnknown = EvidenceEligibilityReason.LEGACY_UNKNOWN.name();
        if (actual.contains(legacyUnknown)) {
            if (actual.size() != 1 || artifactReadable || collectionComplete
                    || baselineRequired || baselineComparable || failureFingerprintMatched
                    || obligationSatisfied) {
                throw new IllegalArgumentException(
                        "LEGACY_UNKNOWN cannot be combined with evaluated eligibility facts");
            }
            return;
        }

        LinkedHashSet<String> expected = new LinkedHashSet<>();
        if (!artifactReadable) {
            expected.add(EvidenceEligibilityReason.ARTIFACT_UNREADABLE.name());
        }
        if (!collectionComplete) {
            expected.add(EvidenceEligibilityReason.COLLECTION_INCOMPLETE.name());
        }
        if (!baselineRequired) {
            expected.add(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name());
        } else if (failureFingerprintMatched) {
            expected.add(EvidenceEligibilityReason.FAILURE_FINGERPRINT_MATCHED.name());
        } else if (baselineComparable) {
            expected.add(EvidenceEligibilityReason.FAILURE_FINGERPRINT_CHANGED.name());
        } else {
            expected.add(EvidenceEligibilityReason.FAILURE_FINGERPRINT_INCOMPARABLE.name());
        }
        if (!obligationSatisfied) {
            expected.add(EvidenceEligibilityReason.OBLIGATION_UNSATISFIED.name());
        }
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "reasonCodes do not match the eligibility dimensions");
        }
    }
}
