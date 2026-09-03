package org.example.algorithmdebug.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TraceProvenance;

/** 将 JDWP Raw JSONL 流式归一化为调用栈和精确值路径摘要。 */
public final class JdwpSnapshotNormalizer {
    private static final List<String> LIFECYCLE_EVENTS = List.of(
            "collector_started", "collector_finished");
    private final BoundedJsonlReader reader;

    public JdwpSnapshotNormalizer() {
        this(new BoundedJsonlReader());
    }

    JdwpSnapshotNormalizer(BoundedJsonlReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("JDWP Normalizer dependency must not be null");
        }
        this.reader = reader;
    }

    public NormalizationResult<JdwpSnapshotSummary> normalize(JdwpNormalizationInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        Accumulator accumulator = null;
        try {
            accumulator = new Accumulator(input);
            reader.read(
                    input.rawTracePath(), input.budget().maxRawBytes(),
                    input.budget().maxRecordBytes(), input.budget().maxRecords(),
                    accumulator::accept);
            return accumulator.finish();
        } catch (NormalizationException failure) {
            long inputRecordCount = accumulator == null ? 0 : accumulator.recordCount;
            return new NormalizationResult<>(
                    NormalizationStatus.FAILED, Optional.empty(), inputRecordCount,
                    0, List.of(), Optional.of(failure.code()), boundedDetail(failure));
        }
    }

    private static String boundedDetail(NormalizationException failure) {
        String detail = failure.getMessage() + "; jsonlLine=" + failure.jsonlLine();
        return detail.length() <= 2_048 ? detail : detail.substring(0, 2_048);
    }

    private static final class Accumulator {
        private final JdwpNormalizationInput input;
        private final Map<String, JdwpTracepointSpec> tracepoints = new HashMap<>();
        private final ArrayList<JdwpSnapshotSummary.TracepointHit> hits = new ArrayList<>();
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private final SummaryBudget summaryBudget;
        private long recordCount;
        private long previousSequence;
        private int projectionCount;

        private Accumulator(JdwpNormalizationInput input) {
            this.input = input;
            this.summaryBudget = new SummaryBudget(input, reasons);
            input.plan().tracepoints().forEach(point -> tracepoints.put(point.tracepointId(), point));
            if (input.collectorTruncated()) reasons.add("COLLECTOR_TRUNCATED");
        }

        private void accept(long line, JsonNode json) {
            recordCount++;
            requireVersion(json, line);
            requiredText(json, "sessionId", 1_024, line);
            validateTimestamp(json, line);
            long sequence = requiredPositiveLong(json, "sequence", line);
            observeSequence(sequence);
            String eventType = requiredText(json, "eventType", 64, line);
            if (LIFECYCLE_EVENTS.contains(eventType)) return;
            if (!"tracepoint_hit".equals(eventType)) {
                throw invalid(line, "Unknown JDWP eventType: " + eventType);
            }
            onHit(json, line, sequence);
        }

        private void observeSequence(long sequence) {
            if ((previousSequence == 0 && sequence != 1)
                    || (previousSequence != 0 && sequence != previousSequence + 1)) {
                reasons.add("SEQUENCE_INCOMPLETE");
            }
            previousSequence = Math.max(previousSequence, sequence);
        }

        private void onHit(JsonNode json, long line, long sequence) {
            String tracepointId = requiredText(json, "tracepointId", 256, line);
            JdwpTracepointSpec tracepoint = tracepoints.get(tracepointId);
            if (tracepoint == null) {
                throw outsidePlan(line, "The JDWP hit does not belong to the collection plan");
            }
            int observedHit = requiredPositiveInt(json, "observedHit", line);
            int matchedHit = requiredPositiveInt(json, "matchedHit", line);
            int capturedHit = requiredPositiveInt(json, "capturedHit", line);
            if (matchedHit > observedHit || capturedHit > matchedHit
                    || observedHit > tracepoint.maxObservedHits()
                    || capturedHit > tracepoint.maxCapturedHits()) {
                throw outsidePlan(line, "The JDWP hit counters violate the collection plan");
            }
            boolean selectedBySampling = matchedHit <= tracepoint.captureFirstMatchedHits()
                    || (tracepoint.captureEveryMatchedHits() > 0
                    && matchedHit % tracepoint.captureEveryMatchedHits() == 0);
            if (!selectedBySampling) {
                throw outsidePlan(line, "The JDWP hit violates the matched-hit sampling policy");
            }
            if (!tracepoint.conditions().isEmpty()
                    && !"MATCHED".equals(json.path("conditionResult").asText())) {
                throw invalid(line, "The conditional JDWP snapshot is not marked MATCHED");
            }

            JsonNode thread = requiredObject(json, "thread", line);
            String threadName = requiredText(thread, "name", 512, line);
            JsonNode location = requiredObject(json, "location", line);
            String className = requiredText(location, "className", 1_024, line);
            String methodName = requiredText(location, "methodName", 512, line);
            int sourceLine = requiredPositiveInt(location, "line", line);
            String descriptor = requiredText(location, "methodDescriptor", 2_048, line);
            long codeIndex = requiredNonNegativeLong(location, "codeIndex", line);
            if (!className.equals(tracepoint.sourceAnchor().className())
                    || !methodName.equals(tracepoint.sourceAnchor().methodName())
                    || sourceLine != tracepoint.line()
                    || !descriptor.equals(tracepoint.sourceAnchor().descriptor())) {
                throw outsidePlan(line, "JDWP location does not match the collection plan");
            }

            JsonNode framesNode = requiredArray(json, "frames", line);
            JsonNode projectionsNode = requiredArray(json, "projections", line);
            if (hits.size() >= input.budget().maxHits()) {
                reasons.add("HIT_BUDGET_EXCEEDED");
                return;
            }
            TraceProvenance provenance = provenance(line, sequence);
            int frameLimit = Math.min(
                    input.budget().maxFramesPerHit(), tracepoint.capture().maxFrames());
            List<JdwpSnapshotSummary.StackFrame> frames = frames(framesNode, frameLimit, line);
            if (framesNode.size() > frameLimit) reasons.add("FRAME_BUDGET_EXCEEDED");
            if (tracepoint.capture().stack() && frames.isEmpty()) reasons.add("STACK_UNAVAILABLE");

            List<JdwpSnapshotSummary.ProjectionFact> projections = projections(
                    projectionsNode, tracepoint, provenance, line);
            if (projectionCount + projections.size() > input.budget().maxValueFacts()) {
                reasons.add("PROJECTION_BUDGET_EXCEEDED");
                return;
            }
            projections.forEach(this::recordProjectionStatus);
            String normalizedLocation = className + "#" + methodName + ":" + sourceLine;
            if (!summaryBudget.reserveHit(
                    tracepointId, threadName, normalizedLocation, frames, projections)) {
                return;
            }
            projectionCount += projections.size();
            hits.add(new JdwpSnapshotSummary.TracepointHit(
                    tracepointId, observedHit, matchedHit, capturedHit,
                    threadName, normalizedLocation, Optional.of(descriptor), Optional.of(codeIndex),
                    frames, projections, provenance));
        }

        private void recordProjectionStatus(JdwpSnapshotSummary.ProjectionFact projection) {
            switch (projection.status()) {
                case CAPTURED -> { }
                case TRUNCATED -> reasons.add("PROJECTION_TRUNCATED");
                case REFERENCE_ONLY -> reasons.add("PROJECTION_REQUIRES_DEEPER_PATH");
                case UNAVAILABLE -> reasons.add("PROJECTION_UNAVAILABLE");
            }
        }

        private List<JdwpSnapshotSummary.StackFrame> frames(
                JsonNode frames, int maximum, long line) {
            ArrayList<JdwpSnapshotSummary.StackFrame> result = new ArrayList<>();
            HashSet<Integer> indexes = new HashSet<>();
            for (int index = 0; index < Math.min(maximum, frames.size()); index++) {
                JsonNode frame = frames.get(index);
                if (!frame.isObject()) throw invalid(line, "JDWP frame must be an object");
                int frameIndex = requiredNonNegativeInt(frame, "index", line);
                if (!indexes.add(frameIndex)) throw invalid(line, "JDWP frame index is duplicated");
                result.add(new JdwpSnapshotSummary.StackFrame(
                        frameIndex,
                        requiredText(frame, "className", 1_024, line),
                        requiredText(frame, "methodName", 512, line),
                        Optional.of(requiredText(frame, "methodDescriptor", 2_048, line)),
                        requiredFrameLine(frame, "line", line),
                        Optional.of(requiredNonNegativeLong(frame, "codeIndex", line))));
            }
            result.sort(Comparator.comparingInt(JdwpSnapshotSummary.StackFrame::index));
            return List.copyOf(result);
        }

        private List<JdwpSnapshotSummary.ProjectionFact> projections(
                JsonNode projections,
                JdwpTracepointSpec tracepoint,
                TraceProvenance provenance,
                long line) {
            Map<String, JsonNode> byPath = new HashMap<>();
            for (JsonNode projection : projections) {
                if (!projection.isObject()) throw invalid(line, "JDWP projection must be an object");
                String path = requiredText(projection, "valuePath", 2_048, line);
                if (!tracepoint.capture().valuePaths().contains(path)) {
                    throw outsidePlan(line, "JDWP projection was not requested by the plan");
                }
                if (byPath.put(path, projection) != null) {
                    throw invalid(line, "JDWP projection valuePath is duplicated");
                }
            }
            if (!byPath.keySet().equals(new HashSet<>(tracepoint.capture().valuePaths()))) {
                throw invalid(line, "JDWP projections do not cover every requested valuePath");
            }
            return tracepoint.capture().valuePaths().stream()
                    .map(path -> projection(byPath.get(path), path, provenance, line))
                    .toList();
        }

        private JdwpSnapshotSummary.ProjectionFact projection(
                JsonNode json, String path, TraceProvenance provenance, long line) {
            JdwpSnapshotSummary.ProjectionStatus status;
            try {
                status = JdwpSnapshotSummary.ProjectionStatus.valueOf(
                        requiredText(json, "status", 32, line));
            } catch (IllegalArgumentException failure) {
                throw invalid(line, "JDWP projection status is invalid");
            }
            return new JdwpSnapshotSummary.ProjectionFact(
                    path,
                    status,
                    optionalText(json, "kind", 64, line),
                    optionalText(json, "runtimeType", 1_024, line),
                    optionalText(json, "scalarValue", input.budget().maxScalarChars(), line),
                    requiredBoolean(json, "valueTruncated", line),
                    optionalNonNegativeLong(json, "objectId", line),
                    optionalText(json, "reason", 256, line),
                    provenance);
        }

        private NormalizationResult<JdwpSnapshotSummary> finish() {
            if (hits.isEmpty()) reasons.add("ZERO_TRACEPOINT_HITS");
            hits.sort(Comparator.comparingLong(hit -> hit.provenance().sequence().orElseThrow()));
            boolean partial = !reasons.isEmpty();
            boolean truncated = input.collectorTruncated() || reasons.stream().anyMatch(reason ->
                    reason.contains("BUDGET") || reason.contains("TRUNCATED")
                    || "SEQUENCE_INCOMPLETE".equals(reason));
            JdwpSnapshotSummary summary = new JdwpSnapshotSummary(
                    SchemaVersions.JDWP_SNAPSHOT_SUMMARY, input.evidenceId(),
                    input.collection().caseId(), input.collection().analysisId(),
                    input.collection().runId(), input.collection().planId(),
                    input.collection().collectionId(), input.rawTrace(),
                    List.copyOf(hits), List.copyOf(reasons), truncated, input.createdAt());
            long frameCount = hits.stream().mapToLong(hit -> hit.frames().size()).sum();
            long emitted = hits.size() + frameCount + projectionCount;
            return new NormalizationResult<>(
                    partial ? NormalizationStatus.PARTIAL : NormalizationStatus.COMPLETE,
                    Optional.of(summary), recordCount, emitted, List.copyOf(reasons),
                    Optional.empty(), "");
        }

        private TraceProvenance provenance(long line, long sequence) {
            return new TraceProvenance(
                    input.collection().caseId(), input.collection().runId(),
                    input.collection().collectionId(), input.rawTrace(), line,
                    Optional.empty(), Optional.of(sequence), "RAW_OBSERVATION");
        }
    }

    private static final class SummaryBudget {
        private static final long SUMMARY_OVERHEAD = 1_536;
        private static final long PROVENANCE_OVERHEAD = 384;
        private final long maximum;
        private final LinkedHashSet<String> reasons;
        private final long provenanceBytes;
        private long reserved;
        private boolean exhausted;

        private SummaryBudget(JdwpNormalizationInput input, LinkedHashSet<String> reasons) {
            this.maximum = input.budget().maxSummaryBytes();
            this.reasons = reasons;
            this.provenanceBytes = PROVENANCE_OVERHEAD
                    + identityBytes(input) + artifactBytes(input.rawTrace())
                    + textBytes("RAW_OBSERVATION");
            this.reserved = SUMMARY_OVERHEAD + identityBytes(input) + artifactBytes(input.rawTrace());
            if (reserved > maximum) {
                throw new NormalizationException(
                        "NORMALIZE_OUTPUT_BUDGET_TOO_SMALL",
                        "The summary budget cannot preserve evidence identity and raw references",
                        0, null);
            }
        }

        private boolean reserveHit(
                String tracepointId,
                String threadName,
                String location,
                List<JdwpSnapshotSummary.StackFrame> frames,
                List<JdwpSnapshotSummary.ProjectionFact> projections) {
            long bytes = 512L + provenanceBytes + textBytes(tracepointId)
                    + textBytes(threadName) + textBytes(location);
            for (JdwpSnapshotSummary.StackFrame frame : frames) {
                bytes += 256L + textBytes(frame.className()) + textBytes(frame.methodName());
            }
            for (JdwpSnapshotSummary.ProjectionFact projection : projections) {
                bytes += 320L + provenanceBytes + textBytes(projection.valuePath());
                bytes += projection.kind().map(SummaryBudget::textBytes).orElse(0L);
                bytes += projection.runtimeType().map(SummaryBudget::textBytes).orElse(0L);
                bytes += projection.scalarValue().map(SummaryBudget::textBytes).orElse(0L);
                bytes += projection.reason().map(SummaryBudget::textBytes).orElse(0L);
            }
            if (exhausted) return false;
            if (bytes > maximum - reserved) {
                exhausted = true;
                reasons.add("OUTPUT_BUDGET_EXCEEDED");
                return false;
            }
            reserved += bytes;
            return true;
        }

        private static long identityBytes(JdwpNormalizationInput input) {
            return textBytes(input.evidenceId().value())
                    + textBytes(input.collection().caseId().value())
                    + textBytes(input.collection().analysisId().value())
                    + textBytes(input.collection().runId().value())
                    + textBytes(input.collection().planId().value())
                    + textBytes(input.collection().collectionId().value());
        }

        private static long artifactBytes(org.example.algorithmdebug.contracts.ArtifactReference value) {
            return 192L + textBytes(value.artifactId()) + textBytes(value.artifactType())
                    + textBytes(value.relativePath()) + textBytes(value.mediaType())
                    + textBytes(value.sha256());
        }

        private static long textBytes(String value) {
            long bytes = value.getBytes(StandardCharsets.UTF_8).length + 2L;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '"' || character == '\\') bytes++;
                else if (character <= 0x1f) bytes += 5L;
            }
            return bytes;
        }
    }

    private static void requireVersion(JsonNode json, long line) {
        if (!"3.0".equals(requiredText(json, "schemaVersion", 32, line))) {
            throw invalid(line, "Unsupported JDWP Raw schemaVersion");
        }
    }

    private static Optional<String> optionalText(
            JsonNode json, String field, int maximum, long line) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || value.textValue().length() > maximum) {
            throw invalid(line, field + " is invalid");
        }
        return Optional.of(value.textValue());
    }

    private static Optional<Long> optionalNonNegativeLong(
            JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(line, field + " is invalid");
        }
        return Optional.of(value.longValue());
    }

    private static long requiredNonNegativeLong(JsonNode json, String field, long line) {
        return optionalNonNegativeLong(json, field, line)
                .orElseThrow(() -> invalid(line, field + " is missing"));
    }

    private static boolean requiredBoolean(JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || !value.isBoolean()) throw invalid(line, field + " is invalid");
        return value.booleanValue();
    }

    private static void validateTimestamp(JsonNode json, long line) {
        try {
            Instant.parse(requiredText(json, "timestamp", 128, line));
        } catch (DateTimeParseException failure) {
            throw new NormalizationException(
                    "NORMALIZE_SCHEMA_UNSUPPORTED", "JDWP timestamp is invalid", line, failure);
        }
    }

    private static JsonNode requiredObject(JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || !value.isObject()) throw invalid(line, field + " is invalid");
        return value;
    }

    private static JsonNode requiredArray(JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || !value.isArray()) throw invalid(line, field + " is invalid");
        return value;
    }

    private static String requiredText(JsonNode json, String field, int maximum, long line) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw invalid(line, field + " is invalid");
        }
        return value.textValue();
    }

    private static long requiredPositiveLong(JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() < 1) {
            throw invalid(line, field + " is invalid");
        }
        return value.longValue();
    }

    private static int requiredPositiveInt(JsonNode json, String field, long line) {
        long value = requiredPositiveLong(json, field, line);
        if (value > Integer.MAX_VALUE) throw invalid(line, field + " exceeds the limit");
        return (int) value;
    }

    private static int requiredNonNegativeInt(JsonNode json, String field, long line) {
        long value = requiredNonNegativeLong(json, field, line);
        if (value > Integer.MAX_VALUE) throw invalid(line, field + " exceeds the limit");
        return (int) value;
    }

    private static int requiredFrameLine(JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < -1) {
            throw invalid(line, field + " is invalid");
        }
        return value.intValue();
    }

    private static NormalizationException outsidePlan(long line, String detail) {
        return new NormalizationException("NORMALIZE_EVENT_OUTSIDE_PLAN", detail, line, null);
    }

    private static NormalizationException invalid(long line, String detail) {
        return new NormalizationException("NORMALIZE_SCHEMA_UNSUPPORTED", detail, line, null);
    }
}
