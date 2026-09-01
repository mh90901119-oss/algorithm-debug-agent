package org.example.algorithmdebug.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TraceProvenance;

/** 将 JDWP Raw JSONL 流式归一化为命中、栈帧和通用值路径摘要。 */
public final class JdwpSnapshotNormalizer {

    private static final List<String> LIFECYCLE_EVENTS = List.of(
            "collector_started", "collector_finished");
    private final BoundedJsonlReader reader;
    private final JdwpValueFlattener valueFlattener;

    /** 使用固定缓冲区 Reader 和通用值扁平器。 */
    public JdwpSnapshotNormalizer() {
        this(new BoundedJsonlReader(), new JdwpValueFlattener());
    }

    JdwpSnapshotNormalizer(BoundedJsonlReader reader, JdwpValueFlattener valueFlattener) {
        if (reader == null || valueFlattener == null) {
            throw new IllegalArgumentException("JDWP Normalizer dependencies must not be null");
        }
        this.reader = reader;
        this.valueFlattener = valueFlattener;
    }

    /**
     * 归一化一条归档的 JDWP Trace；格式失败返回 FAILED 且不伪造空摘要。
     *
     * @param input 已校验身份和预算的 JDWP 输入
     * @return 通用运行时摘要或结构化失败
     */
    public NormalizationResult<JdwpSnapshotSummary> normalize(JdwpNormalizationInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        Accumulator accumulator = null;
        try {
            accumulator = new Accumulator(input, valueFlattener);
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
        private final JdwpValueFlattener flattener;
        private final Map<String, JdwpTracepointSpec> tracepoints = new HashMap<>();
        private final ArrayList<JdwpSnapshotSummary.TracepointHit> hits = new ArrayList<>();
        private final ArrayList<JdwpSnapshotSummary.CollectorLimitFact> limits = new ArrayList<>();
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private final SummaryBudget summaryBudget;
        private long recordCount;
        private long previousSequence;
        private int valueFactCount;
        private String rawSchemaVersion;

        private Accumulator(JdwpNormalizationInput input, JdwpValueFlattener flattener) {
            this.input = input;
            this.flattener = flattener;
            this.summaryBudget = new SummaryBudget(input, reasons);
            input.plan().tracepoints().forEach(point -> tracepoints.put(point.tracepointId(), point));
            if (input.collectorTruncated()) reasons.add("COLLECTOR_TRUNCATED");
        }

        private void accept(long line, JsonNode json) {
            recordCount++;
            String recordVersion = requireVersion(json, line);
            if (rawSchemaVersion == null) {
                rawSchemaVersion = recordVersion;
            } else if (!rawSchemaVersion.equals(recordVersion)) {
                throw invalid(line, "JDWP Raw schemaVersion changed within one trace");
            }
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
                throw new NormalizationException(
                        "NORMALIZE_EVENT_OUTSIDE_PLAN",
                        "The JDWP hit does not belong to the collection plan", line, null);
            }
            int hit = requiredPositiveInt(json, "hit", line);
            int observedHit = json.has("observedHit")
                    ? requiredPositiveInt(json, "observedHit", line) : hit;
            int matchedHit = json.has("matchedHit")
                    ? requiredPositiveInt(json, "matchedHit", line) : hit;
            int capturedHit = json.has("capturedHit")
                    ? requiredPositiveInt(json, "capturedHit", line) : matchedHit;
            if (observedHit > tracepoint.maxObservedHits()) {
                throw invalid(line, "The JDWP observed hit exceeds the plan limit");
            }
            if (capturedHit > tracepoint.maxCapturedHits()) {
                throw invalid(line, "The JDWP captured hit exceeds the plan limit");
            }
            if (!tracepoint.captureOnMatchedHits().isEmpty()
                    && !tracepoint.captureOnMatchedHits().contains(matchedHit)) {
                throw new NormalizationException(
                        "NORMALIZE_EVENT_OUTSIDE_PLAN",
                        "The JDWP hit sequence does not belong to the collection plan", line, null);
            }
            if (tracepoint.condition() != null
                    && !"MATCHED".equals(json.path("conditionResult").asText())) {
                throw invalid(line, "The conditional JDWP snapshot is not marked MATCHED");
            }
            JsonNode thread = requiredObject(json, "thread", line);
            String threadName = requiredText(thread, "name", 512, line);
            JsonNode location = requiredObject(json, "location", line);
            String className = requiredText(location, "className", 1_024, line);
            String methodName = requiredText(location, "methodName", 512, line);
            int sourceLine = requiredLine(location, "line", line);
            Optional<String> methodDescriptor = optionalText(
                    location, "methodDescriptor", 2_048, line);
            Optional<Long> codeIndex = optionalNonNegativeLong(location, "codeIndex", line);
            if ("2.0".equals(rawSchemaVersion)
                    && (methodDescriptor.isEmpty() || codeIndex.isEmpty())) {
                throw invalid(line, "JDWP Raw 2.0 location lacks methodDescriptor or codeIndex");
            }
            if (!className.equals(tracepoint.sourceAnchor().className())
                    || !methodName.equals(tracepoint.sourceAnchor().methodName())
                    || sourceLine != tracepoint.line()
                    || (methodDescriptor.isPresent() && !methodDescriptor.orElseThrow()
                            .equals(tracepoint.sourceAnchor().descriptor()))) {
                throw new NormalizationException(
                        "NORMALIZE_EVENT_OUTSIDE_PLAN",
                        "JDWP location does not match the collection plan", line, null);
            }
            JsonNode framesNode = json.get("frames");
            if (framesNode == null || !framesNode.isArray()) {
                throw invalid(line, "The JDWP hit is missing a frames array");
            }
            if (hits.size() >= input.budget().maxHits()) {
                reasons.add("HIT_BUDGET_EXCEEDED");
                return;
            }
            TraceProvenance provenance = provenance(line, sequence);
            int frameLimit = Math.min(
                    input.budget().maxFramesPerHit(), tracepoint.capture().maxFrames());
            List<JdwpSnapshotSummary.StackFrame> frames = frames(
                    framesNode, frameLimit, line, "2.0".equals(rawSchemaVersion));
            if (framesNode.size() > frameLimit) reasons.add("FRAME_BUDGET_EXCEEDED");
            String normalizedLocation = className + "#" + methodName + ":" + sourceLine;
            if (!summaryBudget.reserveHit(tracepointId, threadName, normalizedLocation, frames)) {
                return;
            }
            List<JdwpValueFlattener.RootValue> roots = roots(framesNode);
            if (!tracepoint.capture().locals() && !roots.isEmpty()) {
                throw new NormalizationException(
                        "NORMALIZE_EVENT_OUTSIDE_PLAN",
                        "The JDWP Raw Trace contains locals/this values not requested by the plan", line, null);
            }
            int remainingFacts = Math.max(0, input.budget().maxValueFacts() - valueFactCount);
            JdwpValueFlattener.Result flattened = flattener.flatten(
                    roots, provenance, input.budget(), remainingFacts);
            reasons.addAll(flattened.reasons());
            ArrayList<JdwpSnapshotSummary.ValueFact> retainedFacts = new ArrayList<>();
            for (JdwpSnapshotSummary.ValueFact fact : flattened.facts()) {
                if (!summaryBudget.reserveValue(fact)) break;
                retainedFacts.add(fact);
            }
            retainedFacts.sort(Comparator.comparing(JdwpSnapshotSummary.ValueFact::valuePath));
            valueFactCount += retainedFacts.size();
            int remainingLimits = Math.max(0, 1_024 - limits.size());
            if (flattened.limits().size() > remainingLimits) {
                reasons.add("LIMIT_FACT_BUDGET_EXCEEDED");
            }
            for (JdwpSnapshotSummary.CollectorLimitFact limit : flattened.limits().subList(
                    0, Math.min(remainingLimits, flattened.limits().size()))) {
                if (!summaryBudget.reserveLimit(limit)) break;
                limits.add(limit);
            }
            hits.add(new JdwpSnapshotSummary.TracepointHit(
                    tracepointId, hit, threadName,
                    normalizedLocation, methodDescriptor, codeIndex,
                    frames, List.copyOf(retainedFacts), provenance));
        }

        private List<JdwpSnapshotSummary.StackFrame> frames(
                JsonNode frames,
                int maximum,
                long line,
                boolean requireV2Location) {
            ArrayList<JdwpSnapshotSummary.StackFrame> result = new ArrayList<>();
            java.util.HashSet<Integer> indexes = new java.util.HashSet<>();
            for (int index = 0; index < Math.min(maximum, frames.size()); index++) {
                JsonNode frame = frames.get(index);
                if (!frame.isObject()) throw invalid(line, "JDWP frame must be object");
                int frameIndex = requiredNonNegativeInt(frame, "index", line);
                if (!indexes.add(frameIndex)) throw invalid(line, "JDWP frame index duplicate");
                Optional<String> descriptor = optionalText(
                        frame, "methodDescriptor", 2_048, line);
                Optional<Long> codeIndex = optionalNonNegativeLong(frame, "codeIndex", line);
                if (requireV2Location && (descriptor.isEmpty() || codeIndex.isEmpty())) {
                    throw invalid(line, "JDWP Raw 2.0 frame lacks methodDescriptor or codeIndex");
                }
                result.add(new JdwpSnapshotSummary.StackFrame(
                        frameIndex,
                        requiredText(frame, "className", 1_024, line),
                        requiredText(frame, "methodName", 512, line),
                        descriptor,
                        requiredFrameLine(frame, "line", line),
                        codeIndex));
            }
            result.sort(Comparator.comparingInt(JdwpSnapshotSummary.StackFrame::index));
            return List.copyOf(result);
        }

        private List<JdwpValueFlattener.RootValue> roots(JsonNode frames) {
            if (frames.isEmpty() || !frames.get(0).isObject()) return List.of();
            JsonNode top = frames.get(0);
            ArrayList<JdwpValueFlattener.RootValue> result = new ArrayList<>();
            JsonNode locals = top.get("locals");
            if (locals != null && !locals.isNull()) {
                if (plainLocalsObject(locals)) {
                    List<String> names = new ArrayList<>();
                    locals.fieldNames().forEachRemaining(names::add);
                    names.sort(Comparator.naturalOrder());
                    names.forEach(name -> result.add(new JdwpValueFlattener.RootValue(
                            "locals." + name, locals.get(name))));
                } else {
                    result.add(new JdwpValueFlattener.RootValue("locals", locals));
                }
            }
            JsonNode thisValue = top.get("this");
            if (thisValue != null && !thisValue.isNull()) {
                result.add(new JdwpValueFlattener.RootValue("this", thisValue));
            }
            return List.copyOf(result);
        }

        private static boolean plainLocalsObject(JsonNode locals) {
            if (!locals.isObject()) return false;
            java.util.Iterator<String> names = locals.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (name.startsWith("$")) return false;
            }
            return true;
        }

        private NormalizationResult<JdwpSnapshotSummary> finish() {
            if (hits.isEmpty()) reasons.add("ZERO_TRACEPOINT_HITS");
            hits.sort(Comparator.comparingLong(hit ->
                    hit.provenance().sequence().orElseThrow()));
            limits.sort(Comparator.comparing(JdwpSnapshotSummary.CollectorLimitFact::valuePath)
                    .thenComparing(JdwpSnapshotSummary.CollectorLimitFact::marker)
                    .thenComparingLong(limit -> limit.provenance().sequence().orElseThrow()));
            boolean partial = !reasons.isEmpty();
            boolean truncated = input.collectorTruncated() || reasons.stream().anyMatch(reason ->
                    !"ZERO_TRACEPOINT_HITS".equals(reason));
            JdwpSnapshotSummary summary = new JdwpSnapshotSummary(
                    SchemaVersions.JDWP_SNAPSHOT_SUMMARY, input.evidenceId(),
                    input.collection().caseId(), input.collection().contextId(),
                    input.collection().analysisId(), input.collection().runId(),
                    input.collection().planId(), input.collection().collectionId(),
                    input.rawTrace(), List.copyOf(hits), List.copyOf(limits),
                    truncated, input.createdAt());
            long frameCount = hits.stream().mapToLong(hit -> hit.frames().size()).sum();
            long emitted = hits.size() + frameCount + valueFactCount + limits.size();
            return new NormalizationResult<>(
                    partial ? NormalizationStatus.PARTIAL : NormalizationStatus.COMPLETE,
                    Optional.of(summary), recordCount, emitted, List.copyOf(reasons),
                    Optional.empty(), "");
        }

        private TraceProvenance provenance(long line, long sequence) {
            return new TraceProvenance(
                    input.collection().caseId(), input.collection().contextId(),
                    input.collection().runId(), input.collection().collectionId(),
                    input.rawTrace(), line, Optional.empty(), Optional.of(sequence),
                    "RAW_OBSERVATION");
        }
    }

    /** 按实际字符串长度保守估算 JSON 大小，达到预算后停止追加事实。 */
    private static final class SummaryBudget {
        private static final long SUMMARY_OVERHEAD = 1_536;
        private static final long PROVENANCE_OVERHEAD = 384;
        private final long maximum;
        private final LinkedHashSet<String> reasons;
        private final long provenanceBytes;
        private long reserved;
        private boolean exhausted;

        private SummaryBudget(
                JdwpNormalizationInput input,
                LinkedHashSet<String> reasons) {
            this.maximum = input.budget().maxSummaryBytes();
            this.reasons = reasons;
            this.provenanceBytes = PROVENANCE_OVERHEAD
                    + identityBytes(input) + artifactBytes(input.rawTrace())
                    + textBytes("RAW_OBSERVATION");
            this.reserved = SUMMARY_OVERHEAD
                    + identityBytes(input) + artifactBytes(input.rawTrace());
            if (reserved > maximum) {
                throw new NormalizationException(
                        "NORMALIZE_OUTPUT_BUDGET_TOO_SMALL",
                        "The summary budget cannot preserve the evidence identity and raw artifact references", 0, null);
            }
        }

        private boolean reserveHit(
                String tracepointId,
                String threadName,
                String location,
                List<JdwpSnapshotSummary.StackFrame> frames) {
            long bytes = 512L + provenanceBytes + textBytes(tracepointId)
                    + textBytes(threadName) + textBytes(location);
            for (JdwpSnapshotSummary.StackFrame frame : frames) {
                bytes += 256L + textBytes(frame.className()) + textBytes(frame.methodName());
            }
            return reserve(bytes);
        }

        private boolean reserveValue(JdwpSnapshotSummary.ValueFact fact) {
            long bytes = 320L + provenanceBytes
                    + textBytes(fact.valuePath()) + textBytes(fact.kind())
                    + textBytes(fact.scalarPreview());
            if (fact.runtimeType().isPresent()) bytes += textBytes(fact.runtimeType().orElseThrow());
            for (String marker : fact.collectorMarkers()) bytes += textBytes(marker) + 8L;
            return reserve(bytes);
        }

        private boolean reserveLimit(JdwpSnapshotSummary.CollectorLimitFact limit) {
            return reserve(256L + provenanceBytes
                    + textBytes(limit.valuePath()) + textBytes(limit.marker())
                    + textBytes(limit.detail()));
        }

        private boolean reserve(long bytes) {
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
                    + textBytes(input.collection().contextId().value())
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

    private static String requireVersion(JsonNode json, long line) {
        String version = requiredText(json, "schemaVersion", 32, line);
        if (!"1.0".equals(version) && !"2.0".equals(version)) {
            throw invalid(line, "Unsupported JDWP Raw schemaVersion");
        }
        return version;
    }

    private static Optional<String> optionalText(
            JsonNode json, String field, int maximum, long line) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw invalid(line, field + " invalid");
        }
        return Optional.of(value.textValue());
    }

    private static Optional<Long> optionalNonNegativeLong(
            JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(line, field + " invalid");
        }
        return Optional.of(value.longValue());
    }

    private static void validateTimestamp(JsonNode json, long line) {
        String value = requiredText(json, "timestamp", 128, line);
        try {
            Instant.parse(value);
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
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid(line, field + " is invalid");
        }
        return value.intValue();
    }

    private static int requiredLine(JsonNode json, String field, long line) {
        return requiredPositiveInt(json, field, line);
    }

    private static int requiredFrameLine(JsonNode json, String field, long line) {
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < -1) {
            throw invalid(line, field + " is invalid");
        }
        return value.intValue();
    }

    private static NormalizationException invalid(long line, String detail) {
        return new NormalizationException("NORMALIZE_SCHEMA_UNSUPPORTED", detail, line, null);
    }
}
