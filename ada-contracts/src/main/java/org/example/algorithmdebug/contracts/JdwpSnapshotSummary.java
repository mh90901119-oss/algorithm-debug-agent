package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDWP Raw Trace 的通用、有界运行时事实摘要。 */
public record JdwpSnapshotSummary(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        ArtifactReference rawTrace,
        List<TracepointHit> hits,
        List<CollectorLimitFact> limits,
        boolean truncated,
        Instant createdAt) {

    /** 校验身份、引用和摘要硬上限。 */
    public JdwpSnapshotSummary {
        if (!SchemaVersions.JDWP_SNAPSHOT_SUMMARY.equals(schemaVersion)
                && !SchemaVersions.JDWP_SNAPSHOT_SUMMARY_V1.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 JdwpSnapshotSummary schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        rawTrace = ContractChecks.requireNonNull(rawTrace, "rawTrace");
        hits = ContractChecks.immutableList(hits, "hits");
        limits = ContractChecks.immutableList(limits, "limits");
        if (hits.size() > NormalizationBudget.MAX_HITS || limits.size() > 1_024) {
            throw new IllegalArgumentException("JDWP 摘要条目超过硬上限");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    /** 一次 tracepoint 命中的线程、位置、栈和值事实。 */
    public record TracepointHit(
            String tracepointId, int hit, String threadName, String location,
            Optional<String> methodDescriptor, Optional<Long> codeIndex,
            List<StackFrame> frames, List<ValueFact> values, TraceProvenance provenance) {

        /** 兼容 1.0 摘要和既有调用方。 */
        public TracepointHit(
                String tracepointId, int hit, String threadName, String location,
                List<StackFrame> frames, List<ValueFact> values, TraceProvenance provenance) {
            this(tracepointId, hit, threadName, location, Optional.empty(), Optional.empty(),
                    frames, values, provenance);
        }

        public TracepointHit {
            tracepointId = ContractChecks.requireOpaqueId(tracepointId, "tracepointId");
            if (hit < 1) throw new IllegalArgumentException("hit 必须为正数");
            threadName = ContractChecks.requireBoundedText(threadName, "threadName", 512, false);
            location = ContractChecks.requireBoundedText(location, "location", 1_024, false);
            methodDescriptor = methodDescriptor == null ? Optional.empty() : methodDescriptor
                    .map(value -> ContractChecks.requireBoundedText(
                            value, "methodDescriptor", 2_048, false));
            codeIndex = codeIndex == null ? Optional.empty() : codeIndex;
            if (codeIndex.isPresent() && codeIndex.orElseThrow() < 0) {
                throw new IllegalArgumentException("codeIndex must not be negative");
            }
            frames = ContractChecks.immutableList(frames, "frames");
            values = ContractChecks.immutableList(values, "values");
            if (frames.size() > NormalizationBudget.MAX_FRAMES_PER_HIT
                    || values.size() > NormalizationBudget.MAX_VALUE_FACTS) {
                throw new IllegalArgumentException("命中摘要超过硬上限");
            }
            provenance = ContractChecks.requireNonNull(provenance, "provenance");
        }
    }

    /** 一层通用调用栈事实。 */
    public record StackFrame(
            int index,
            String className,
            String methodName,
            Optional<String> methodDescriptor,
            int line,
            Optional<Long> codeIndex) {

        /** 兼容 1.0 摘要和既有调用方。 */
        public StackFrame(int index, String className, String methodName, int line) {
            this(index, className, methodName, Optional.empty(), line, Optional.empty());
        }

        public StackFrame {
            if (index < 0 || line < -1) throw new IllegalArgumentException("frame 位置非法");
            className = ContractChecks.requireBoundedText(className, "className", 1_024, false);
            methodName = ContractChecks.requireBoundedText(methodName, "methodName", 512, false);
            methodDescriptor = methodDescriptor == null ? Optional.empty() : methodDescriptor
                    .map(value -> ContractChecks.requireBoundedText(
                            value, "methodDescriptor", 2_048, false));
            codeIndex = codeIndex == null ? Optional.empty() : codeIndex;
            if (codeIndex.isPresent() && codeIndex.orElseThrow() < 0) {
                throw new IllegalArgumentException("frame codeIndex must not be negative");
            }
        }
    }

    /** 一个已经由 Collector 捕获的通用值路径与有界预览。 */
    public record ValueFact(
            String valuePath, String kind, Optional<String> runtimeType,
            String scalarPreview, boolean previewTruncated,
            List<String> collectorMarkers, TraceProvenance provenance) {
        public ValueFact {
            valuePath = ContractChecks.requireBoundedText(valuePath, "valuePath", 2_048, false);
            kind = ContractChecks.requireBoundedText(kind, "kind", 64, false);
            runtimeType = ContractChecks.requireNonNull(runtimeType, "runtimeType")
                    .map(value -> ContractChecks.requireBoundedText(
                            value, "runtimeType value", 1_024, false));
            scalarPreview = ContractChecks.requireBoundedText(
                    scalarPreview, "scalarPreview", NormalizationBudget.MAX_SCALAR_CHARS, true);
            collectorMarkers = ContractChecks.immutableBoundedStrings(
                    collectorMarkers, "collectorMarkers", 256);
            if (collectorMarkers.size() > 16) {
                throw new IllegalArgumentException("collectorMarkers 不能超过 16 项");
            }
            provenance = ContractChecks.requireNonNull(provenance, "provenance");
        }
    }

    /** Collector 主动披露的截断、循环、剩余或错误事实。 */
    public record CollectorLimitFact(String valuePath, String marker, String detail,
                                     TraceProvenance provenance) {
        public CollectorLimitFact {
            valuePath = ContractChecks.requireBoundedText(valuePath, "valuePath", 2_048, false);
            marker = ContractChecks.requireBoundedText(marker, "marker", 128, false);
            detail = ContractChecks.requireBoundedText(detail, "detail", 1_024, true);
            provenance = ContractChecks.requireNonNull(provenance, "provenance");
        }
    }
}
