package org.example.algorithmdebug.codepath;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 流式过滤完成后的确定性计数、Hash 与截断状态。 */
public record MethodPathFilterResult(
        long rawEventCount,
        long retainedEventCount,
        long rawBytes,
        long filteredBytes,
        String filteredSha256,
        long exactDescriptorMatchCount,
        long degradedClassMethodMatchCount,
        boolean truncated,
        Optional<String> truncationReason) {

    /** 校验计数与截断说明一致。 */
    public MethodPathFilterResult {
        if (rawEventCount < 0 || retainedEventCount < 0 || retainedEventCount > rawEventCount
                || rawBytes < 0 || filteredBytes < 0 || exactDescriptorMatchCount < 0
                || degradedClassMethodMatchCount < 0
                || exactDescriptorMatchCount + degradedClassMethodMatchCount != retainedEventCount) {
            throw new IllegalArgumentException("过滤计数非法");
        }
        if (filteredSha256 == null || !filteredSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("filteredSha256 非法");
        }
        filteredSha256 = filteredSha256.toLowerCase(Locale.ROOT);
        truncationReason = Objects.requireNonNull(truncationReason, "truncationReason");
        if (truncated != truncationReason.isPresent()) {
            throw new IllegalArgumentException("截断状态与原因不一致");
        }
    }
}
