package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;

/**
 * 对已归档动态证据执行确定性筛选的可选条件。
 *
 * <p>该契约只描述运行时证据的结构字段，不解释目标算法的业务语义。</p>
 */
public record EvidenceQueryFilter(
        Optional<String> methodRef,
        Optional<String> tracepointId,
        Optional<String> valueName,
        Optional<String> scalarValue,
        Optional<String> valueStatus,
        Optional<Long> sequenceFrom,
        Optional<Long> sequenceTo) {

    private static final List<String> VALUE_STATUSES = List.of(
            "VALUE", "NULL", "UNAVAILABLE", "TRUNCATED",
            "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "OBJECT", "ARRAY");

    /** 校验字段长度、状态白名单和序号范围。 */
    public EvidenceQueryFilter {
        methodRef = text(methodRef, "methodRef", 2_048);
        tracepointId = text(tracepointId, "tracepointId", 256);
        valueName = text(valueName, "valueName", 2_048);
        scalarValue = text(scalarValue, "scalarValue", 4_096);
        valueStatus = text(valueStatus, "valueStatus", 64);
        if (valueStatus.isPresent() && !VALUE_STATUSES.contains(valueStatus.orElseThrow())) {
            throw new IllegalArgumentException("valueStatus is not supported");
        }
        sequenceFrom = sequenceFrom == null ? Optional.empty() : sequenceFrom;
        sequenceTo = sequenceTo == null ? Optional.empty() : sequenceTo;
        if (sequenceFrom.filter(value -> value < 1).isPresent()
                || sequenceTo.filter(value -> value < 1).isPresent()
                || (sequenceFrom.isPresent() && sequenceTo.isPresent()
                && sequenceFrom.orElseThrow() > sequenceTo.orElseThrow())) {
            throw new IllegalArgumentException("Evidence query sequence range is invalid");
        }
    }

    /** 返回不限制记录的筛选条件。 */
    public static EvidenceQueryFilter none() {
        return new EvidenceQueryFilter(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Optional<String> text(Optional<String> value, String field, int maximum) {
        if (value == null || value.isEmpty()) return Optional.empty();
        return Optional.of(ContractChecks.requireBoundedText(
                value.orElseThrow(), field, maximum, false));
    }
}
