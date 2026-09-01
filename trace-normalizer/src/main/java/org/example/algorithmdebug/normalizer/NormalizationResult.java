package org.example.algorithmdebug.normalizer;

import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.NormalizationStatus;

/** Normalizer 的内存结果；Core 在摘要归档后据此创建版本化 Manifest。 */
public record NormalizationResult<T>(
        NormalizationStatus status,
        Optional<T> summary,
        long inputRecordCount,
        long emittedFactCount,
        List<String> truncationReasons,
        Optional<String> failureCode,
        String failureDetail) {

    /** 校验完成、部分和失败结果的一致性。 */
    public NormalizationResult {
        if (status == null || summary == null || truncationReasons == null
                || failureCode == null || failureDetail == null
                || inputRecordCount < 0 || emittedFactCount < 0) {
            throw new IllegalArgumentException("NormalizationResult parameters are invalid");
        }
        truncationReasons = List.copyOf(truncationReasons);
        if (truncationReasons.stream().anyMatch(value -> value == null || value.isBlank())
                || truncationReasons.size() > 32 || failureDetail.length() > 2_048) {
            throw new IllegalArgumentException("The NormalizationResult diagnostic exceeds the limit");
        }
        if (status == NormalizationStatus.COMPLETE
                && (summary.isEmpty() || !truncationReasons.isEmpty() || failureCode.isPresent())) {
            throw new IllegalArgumentException("COMPLETE result is inconsistent");
        }
        if (status == NormalizationStatus.PARTIAL
                && (summary.isEmpty() || truncationReasons.isEmpty() || failureCode.isPresent())) {
            throw new IllegalArgumentException("PARTIAL result is inconsistent");
        }
        if (status == NormalizationStatus.FAILED
                && (summary.isPresent() || failureCode.isEmpty())) {
            throw new IllegalArgumentException("FAILED result is inconsistent");
        }
    }
}
