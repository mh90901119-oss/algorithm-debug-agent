package org.example.algorithmdebug.contracts;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * 面向大模型的有界动态证据查询请求。
 * 未被当前 mode 使用的字段保留默认值，避免产生多套命令协议。
 */
public record EvidenceQueryRequest(
        EvidenceQueryMode mode,
        EvidenceQueryFilter filter,
        List<EvidenceValuePredicate> predicates,
        Optional<Long> anchorSequence,
        int beforeRecords,
        int afterRecords,
        Optional<EvidenceQueryGroupBy> groupBy,
        Optional<String> groupValueName,
        int topN,
        List<String> changeValueNames,
        int offset,
        int limit,
        int maxBytes) {

    public EvidenceQueryRequest {
        mode = ContractChecks.requireNonNull(mode, "mode");
        filter = filter == null ? EvidenceQueryFilter.none() : filter;
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
        anchorSequence = anchorSequence == null ? Optional.empty() : anchorSequence;
        groupBy = groupBy == null ? Optional.empty() : groupBy;
        groupValueName = bounded(groupValueName, "groupValueName", 2_048);
        changeValueNames = changeValueNames == null ? List.of() : List.copyOf(changeValueNames);
        if (predicates.size() > 8 || beforeRecords < 0 || beforeRecords > 50
                || afterRecords < 0 || afterRecords > 50 || topN < 1 || topN > 50
                || changeValueNames.size() > 16 || offset < 0 || limit < 1 || limit > 50
                || maxBytes < 1 || maxBytes > 65_536) {
            throw new IllegalArgumentException("Evidence Query request exceeds a hard limit");
        }
        requireUnique(predicates.stream().map(EvidenceValuePredicate::valueName).toList(),
                "predicates must not repeat valueName");
        requireUnique(changeValueNames, "changeValueNames must be unique");
        changeValueNames.forEach(value ->
                ContractChecks.requireBoundedText(value, "changeValueName", 2_048, false));
        if (mode == EvidenceQueryMode.WINDOW
                && anchorSequence.filter(value -> value > 0).isEmpty()) {
            throw new IllegalArgumentException("WINDOW requires a positive anchorSequence");
        }
        if (mode == EvidenceQueryMode.COUNT && groupBy.isEmpty()) {
            throw new IllegalArgumentException("COUNT requires groupBy");
        }
        if (mode == EvidenceQueryMode.COUNT
                && (groupBy.orElseThrow() == EvidenceQueryGroupBy.PROJECTION_VALUE
                || groupBy.orElseThrow() == EvidenceQueryGroupBy.VALUE_STATUS)
                && groupValueName.isEmpty()) {
            throw new IllegalArgumentException(
                    "Projection COUNT requires groupValueName");
        }
        if (mode == EvidenceQueryMode.CHANGES
                && (filter.tracepointId().isEmpty() || changeValueNames.isEmpty())) {
            throw new IllegalArgumentException(
                    "CHANGES requires tracepointId and changeValueNames");
        }
    }

    public static EvidenceQueryRequest summary(int maxBytes) {
        return base(EvidenceQueryMode.SUMMARY, EvidenceQueryFilter.none(), maxBytes);
    }

    public static EvidenceQueryRequest filter(
            EvidenceQueryFilter filter,
            List<EvidenceValuePredicate> predicates,
            int offset,
            int limit,
            int maxBytes) {
        return new EvidenceQueryRequest(
                EvidenceQueryMode.FILTER, filter, predicates, Optional.empty(), 0, 0,
                Optional.empty(), Optional.empty(), 10, List.of(), offset, limit, maxBytes);
    }

    public static EvidenceQueryRequest window(
            EvidenceQueryFilter filter,
            long anchorSequence,
            int beforeRecords,
            int afterRecords,
            int maxBytes) {
        return new EvidenceQueryRequest(
                EvidenceQueryMode.WINDOW, filter, List.of(), Optional.of(anchorSequence),
                beforeRecords, afterRecords, Optional.empty(), Optional.empty(), 10,
                List.of(), 0, Math.min(50, beforeRecords + afterRecords + 1), maxBytes);
    }

    public static EvidenceQueryRequest count(
            EvidenceQueryFilter filter,
            EvidenceQueryGroupBy groupBy,
            Optional<String> groupValueName,
            int topN,
            int maxBytes) {
        return new EvidenceQueryRequest(
                EvidenceQueryMode.COUNT, filter, List.of(), Optional.empty(), 0, 0,
                Optional.of(groupBy), groupValueName, topN, List.of(), 0, 1, maxBytes);
    }

    public static EvidenceQueryRequest changes(
            EvidenceQueryFilter filter,
            List<String> changeValueNames,
            int maxBytes) {
        return new EvidenceQueryRequest(
                EvidenceQueryMode.CHANGES, filter, List.of(), Optional.empty(), 0, 0,
                Optional.empty(), Optional.empty(), 10, changeValueNames, 0, 50, maxBytes);
    }

    private static EvidenceQueryRequest base(
            EvidenceQueryMode mode, EvidenceQueryFilter filter, int maxBytes) {
        return new EvidenceQueryRequest(
                mode, filter, List.of(), Optional.empty(), 0, 0,
                Optional.empty(), Optional.empty(), 10, List.of(), 0, 20, maxBytes);
    }

    private static Optional<String> bounded(
            Optional<String> value, String field, int maximum) {
        if (value == null || value.isEmpty()) return Optional.empty();
        return Optional.of(ContractChecks.requireBoundedText(
                value.orElseThrow(), field, maximum, false));
    }

    private static void requireUnique(List<String> values, String message) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(message);
        }
    }
}
