package org.example.algorithmdebug.contracts;

import java.util.Optional;

/**
 * 将一个派生事实定位回不可变 Raw Trace 的精确位置。
 *
 * @param caseId 所属 Case
 * @param runId 采集运行
 * @param collectionId 采集身份
 * @param rawArtifact Raw Trace 引用
 * @param jsonlLine 从 1 开始的 JSONL 行号
 * @param eventId CodePath 事件 ID
 * @param sequence JDWP 事件序号
 * @param observationKind 直接观察或确定性聚合类型
 */
public record TraceProvenance(
        CaseId caseId,
        RunId runId,
        CollectionId collectionId,
        ArtifactReference rawArtifact,
        long jsonlLine,
        Optional<Long> eventId,
        Optional<Long> sequence,
        String observationKind) {

    /** 校验身份、行号和工具事件定位。 */
    public TraceProvenance {
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        rawArtifact = ContractChecks.requireNonNull(rawArtifact, "rawArtifact");
        if (jsonlLine < 1) {
            throw new IllegalArgumentException("jsonlLine must start at 1");
        }
        eventId = positiveOptional(eventId, "eventId");
        sequence = positiveOptional(sequence, "sequence");
        if (eventId.isPresent() && sequence.isPresent()) {
            throw new IllegalArgumentException("The same source must not contain both eventId and sequence");
        }
        observationKind = ContractChecks.requireBoundedText(
                observationKind, "observationKind", 64, false);
    }

    private static Optional<Long> positiveOptional(Optional<Long> value, String field) {
        Optional<Long> checked = ContractChecks.requireNonNull(value, field);
        checked.ifPresent(number -> {
            if (number < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
        });
        return checked;
    }
}
