package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDWP Raw Trace 的通用、有界运行时事实摘要。 */
public record JdwpSnapshotSummary(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        ArtifactReference rawTrace,
        List<TracepointHit> hits,
        List<String> limitations,
        boolean truncated,
        Instant createdAt) {

    public JdwpSnapshotSummary {
        if (!SchemaVersions.JDWP_SNAPSHOT_SUMMARY.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported JdwpSnapshotSummary schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        rawTrace = ContractChecks.requireNonNull(rawTrace, "rawTrace");
        hits = ContractChecks.immutableList(hits, "hits");
        limitations = ContractChecks.immutableBoundedStrings(
                limitations, "limitations", 256);
        long projectionCount = hits.stream().mapToLong(hit -> hit.projections().size()).sum();
        if (hits.size() > NormalizationBudget.MAX_HITS
                || projectionCount > NormalizationBudget.MAX_VALUE_FACTS
                || limitations.size() > 128) {
            throw new IllegalArgumentException("JDWP summary entries exceed the hard limit");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    /** 一次命中中经过采样后的调用栈和精确值投影。 */
    public record TracepointHit(
            String tracepointId,
            int observedHit,
            int matchedHit,
            int capturedHit,
            String threadName,
            String location,
            Optional<String> methodDescriptor,
            Optional<Long> codeIndex,
            List<StackFrame> frames,
            List<ProjectionFact> projections,
            TraceProvenance provenance) {

        public TracepointHit {
            tracepointId = ContractChecks.requireOpaqueId(tracepointId, "tracepointId");
            if (observedHit < 1 || matchedHit < 1 || capturedHit < 1
                    || matchedHit > observedHit || capturedHit > matchedHit) {
                throw new IllegalArgumentException("JDWP hit counters are invalid");
            }
            threadName = ContractChecks.requireBoundedText(
                    threadName, "threadName", 512, false);
            location = ContractChecks.requireBoundedText(location, "location", 1_024, false);
            methodDescriptor = methodDescriptor == null ? Optional.empty() : methodDescriptor
                    .map(value -> ContractChecks.requireBoundedText(
                            value, "methodDescriptor", 2_048, false));
            codeIndex = codeIndex == null ? Optional.empty() : codeIndex;
            if (codeIndex.isPresent() && codeIndex.orElseThrow() < 0) {
                throw new IllegalArgumentException("codeIndex must not be negative");
            }
            frames = ContractChecks.immutableList(frames, "frames");
            projections = ContractChecks.immutableList(projections, "projections");
            if (frames.size() > NormalizationBudget.MAX_FRAMES_PER_HIT
                    || projections.size() > 128) {
                throw new IllegalArgumentException("JDWP hit entries exceed the hard limit");
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

        public StackFrame {
            if (index < 0 || line < -1) {
                throw new IllegalArgumentException("frame position is invalid");
            }
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

    /** 计划中一个精确值路径的确定性读取结果。 */
    public record ProjectionFact(
            String valuePath,
            ProjectionStatus status,
            Optional<String> kind,
            Optional<String> runtimeType,
            Optional<String> scalarValue,
            boolean valueTruncated,
            Optional<Long> objectId,
            Optional<String> reason,
            TraceProvenance provenance) {

        public ProjectionFact {
            valuePath = ContractChecks.requireBoundedText(valuePath, "valuePath", 2_048, false);
            status = ContractChecks.requireNonNull(status, "status");
            kind = bounded(kind, "kind", 64);
            runtimeType = bounded(runtimeType, "runtimeType", 1_024);
            scalarValue = bounded(scalarValue, "scalarValue", NormalizationBudget.MAX_SCALAR_CHARS);
            objectId = objectId == null ? Optional.empty() : objectId;
            if (objectId.isPresent() && objectId.orElseThrow() < 0) {
                throw new IllegalArgumentException("objectId must not be negative");
            }
            reason = bounded(reason, "reason", 256);
            validateProjection(status, kind, scalarValue, valueTruncated, reason);
            provenance = ContractChecks.requireNonNull(provenance, "provenance");
        }

        private static Optional<String> bounded(
                Optional<String> value, String name, int maximum) {
            return value == null ? Optional.empty() : value.map(text ->
                    ContractChecks.requireBoundedText(text, name, maximum, true));
        }

        private static void validateProjection(
                ProjectionStatus status,
                Optional<String> kind,
                Optional<String> scalarValue,
                boolean valueTruncated,
                Optional<String> reason) {
            if (status == ProjectionStatus.UNAVAILABLE
                    && (reason.isEmpty() || kind.isPresent() || scalarValue.isPresent())) {
                throw new IllegalArgumentException("UNAVAILABLE projection fields are invalid");
            }
            if (status == ProjectionStatus.REFERENCE_ONLY
                    && (kind.isEmpty() || scalarValue.isPresent() || reason.isEmpty())) {
                throw new IllegalArgumentException("REFERENCE_ONLY projection fields are invalid");
            }
            if (status == ProjectionStatus.TRUNCATED
                    && (kind.isEmpty() || scalarValue.isEmpty() || !valueTruncated
                    || reason.isEmpty())) {
                throw new IllegalArgumentException("TRUNCATED projection fields are invalid");
            }
            if (status == ProjectionStatus.CAPTURED
                    && (kind.isEmpty() || valueTruncated || reason.isPresent())) {
                throw new IllegalArgumentException("CAPTURED projection fields are invalid");
            }
        }
    }

    public enum ProjectionStatus { CAPTURED, TRUNCATED, REFERENCE_ONLY, UNAVAILABLE }
}
