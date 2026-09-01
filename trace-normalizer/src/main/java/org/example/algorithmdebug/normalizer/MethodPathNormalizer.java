package org.example.algorithmdebug.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TraceProvenance;

/** 将 CodePath filtered JSONL 流式聚合为通用方法统计和最近保留祖先关系。 */
public final class MethodPathNormalizer {

    private static final String STRUCTURE_INCOMPLETE = "TRACE_STRUCTURE_INCOMPLETE";
    private final BoundedJsonlReader reader;

    /** 使用默认有界 JSONL Reader。 */
    public MethodPathNormalizer() {
        this(new BoundedJsonlReader());
    }

    MethodPathNormalizer(BoundedJsonlReader reader) {
        if (reader == null) throw new IllegalArgumentException("reader must not be null");
        this.reader = reader;
    }

    /**
     * 归一化一条 CodePath 精确方法 Raw Trace；格式失败返回 FAILED，不伪造空摘要。
     *
     * @param input 已校验身份和预算的归一化输入
     * @return 通用摘要或结构化失败
     */
    public NormalizationResult<MethodPathSummary> normalize(CodePathNormalizationInput input) {
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

        private final CodePathNormalizationInput input;
        private final Map<String, MethodSelector> exactMethods = new HashMap<>();
        private final Map<String, MutableMethod> methods = new LinkedHashMap<>();
        private final Map<PathKey, MutablePath> paths = new LinkedHashMap<>();
        private final Deque<OpenCall> stack = new ArrayDeque<>();
        private final Optional<String> scopeMethodKey;
        private final List<MutableScopeInvocation> scopeInvocations = new ArrayList<>();
        private final Deque<MutableScopeInvocation> openScopes = new ArrayDeque<>();
        private final List<MethodPathSummary.PathAnomaly> anomalies = new ArrayList<>();
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private final SummaryBudget summaryBudget;
        private long recordCount;
        private long previousEventId;

        private Accumulator(CodePathNormalizationInput input) {
            this.input = input;
            this.summaryBudget = new SummaryBudget(input, reasons);
            for (MethodSelector selector : input.plan().selectors()) {
                exactMethods.put(selector.methodKey(), selector);
            }
            this.scopeMethodKey = input.plan().scopeMethodKey();
            if (input.collectorTruncated()) reasons.add("COLLECTOR_TRUNCATED");
        }

        private void accept(long line, JsonNode json) {
            Event event = Event.parse(json, line);
            recordCount++;
            TraceProvenance provenance = provenance(line, event.eventId);
            if (event.eventId <= previousEventId) {
                anomaly("EVENT_ID_NOT_INCREASING", "eventId=" + event.eventId, provenance);
                reasons.add("EVENT_ID_ORDER_INVALID");
            }
            previousEventId = Math.max(previousEventId, event.eventId);
            String methodKey = resolveMethod(event, line);
            MutableMethod method = methods.get(methodKey);
            if (method == null) {
                boolean emitted = summaryBudget.reserve(summaryBudget.methodBytes(methodKey));
                method = new MutableMethod(methodKey, emitted);
                methods.put(methodKey, method);
            }
            method.observe(event, provenance);
            observeScope(event, methodKey);
            if ("METHOD_ENTER".equals(event.eventType)) {
                onEnter(event, methodKey, provenance);
            } else {
                onExit(event, methodKey, provenance);
            }
        }

        private void observeScope(Event event, String methodKey) {
            MutableScopeInvocation current = openScopes.peekLast();
            if (current != null) {
                current.observe(event, methodKey);
            }
            if ("METHOD_ENTER".equals(event.eventType)
                    && scopeMethodKey.filter(methodKey::equals).isPresent()) {
                if (scopeInvocations.size() >= input.budget().maxHits()) {
                    reasons.add("SCOPE_INVOCATION_BUDGET_EXCEEDED");
                    return;
                }
                MutableScopeInvocation invocation = new MutableScopeInvocation(
                        scopeInvocations.size() + 1, event.eventId, event.depth);
                invocation.observe(event, methodKey);
                scopeInvocations.add(invocation);
                openScopes.addLast(invocation);
            } else if ("METHOD_EXIT".equals(event.eventType)
                    && scopeMethodKey.filter(methodKey::equals).isPresent()
                    && current != null
                    && current.startDepth == event.depth) {
                current.endEventId = event.eventId;
                openScopes.removeLast();
            }
        }

        private String resolveMethod(Event event, long line) {
            String exact = event.className + "#" + event.methodName + event.descriptor;
            if (!exactMethods.containsKey(exact)) {
                throw new NormalizationException(
                        "NORMALIZE_EVENT_OUTSIDE_PLAN",
                        "The CodePath event does not belong to a planned method", line, null);
            }
            return exact;
        }

        private void onEnter(Event event, String methodKey, TraceProvenance provenance) {
            OpenCall ancestor = stack.peekLast();
            if (ancestor != null && ancestor.depth < event.depth) {
                addPath(ancestor.methodKey, methodKey, provenance);
            } else if (ancestor != null) {
                anomaly("NON_NESTED_ENTER",
                        "depth=" + event.depth + "; openDepth=" + ancestor.depth, provenance);
                reasons.add(STRUCTURE_INCOMPLETE);
            }
            stack.addLast(new OpenCall(methodKey, event.depth, provenance));
        }

        private void onExit(Event event, String methodKey, TraceProvenance provenance) {
            OpenCall open = stack.peekLast();
            if (open != null && open.methodKey.equals(methodKey) && open.depth == event.depth) {
                stack.removeLast();
                return;
            }
            anomaly("UNMATCHED_EXIT", "method=" + methodKey + "; depth=" + event.depth, provenance);
            reasons.add(STRUCTURE_INCOMPLETE);
        }

        private void addPath(
                String ancestor,
                String descendant,
                TraceProvenance provenance) {
            PathKey key = new PathKey(ancestor, descendant);
            MutablePath existing = paths.get(key);
            if (existing != null) {
                existing.count++;
                return;
            }
            MutableMethod ancestorMethod = methods.get(ancestor);
            MutableMethod descendantMethod = methods.get(descendant);
            if (ancestorMethod == null || descendantMethod == null
                    || !ancestorMethod.emitted || !descendantMethod.emitted) {
                return;
            }
            if (paths.size() >= input.budget().maxRelationships()) {
                reasons.add("RELATIONSHIP_BUDGET_EXCEEDED");
                return;
            }
            if (!summaryBudget.reserve(
                    summaryBudget.pathBytes(ancestor, descendant))) {
                return;
            }
            paths.put(key, new MutablePath(key, provenance));
        }

        private void anomaly(String code, String detail, TraceProvenance provenance) {
            if (anomalies.size() >= 10_000) {
                reasons.add("ANOMALY_BUDGET_EXCEEDED");
                return;
            }
            if (!summaryBudget.reserve(summaryBudget.anomalyBytes(code, detail))) return;
            anomalies.add(new MethodPathSummary.PathAnomaly(code, detail, provenance));
        }

        private NormalizationResult<MethodPathSummary> finish() {
            stack.forEach(open -> {
                anomaly("OPEN_ENTER_AT_EOF", "method=" + open.methodKey, open.provenance);
                reasons.add(STRUCTURE_INCOMPLETE);
            });
            if (recordCount == 0) reasons.add("ZERO_RETAINED_EVENTS");
            List<MethodPathSummary.MethodStatistic> methodFacts = methods.values().stream()
                    .filter(value -> value.emitted)
                    .map(MutableMethod::toFact)
                    .sorted(Comparator.comparing(MethodPathSummary.MethodStatistic::methodKey))
                    .toList();
            List<MethodPathSummary.ObservedPath> pathFacts = paths.values().stream()
                    .map(MutablePath::toFact)
                    .sorted(Comparator.comparing(MethodPathSummary.ObservedPath::ancestorMethodKey)
                            .thenComparing(MethodPathSummary.ObservedPath::descendantMethodKey))
                    .toList();
            List<MethodPathSummary.PathAnomaly> anomalyFacts = anomalies.stream()
                    .sorted(Comparator.comparing(MethodPathSummary.PathAnomaly::code)
                            .thenComparingLong(value -> value.provenance().jsonlLine()))
                    .toList();
            Optional<MethodPathSummary.ScopeSummary> scopeFact = buildScopeSummary();
            boolean partial = !reasons.isEmpty();
            boolean summaryTruncated = input.collectorTruncated()
                    || reasons.stream().anyMatch(value -> value.endsWith("BUDGET_EXCEEDED"));
            MethodPathSummary summary = new MethodPathSummary(
                    SchemaVersions.METHOD_PATH_SUMMARY, input.evidenceId(),
                    input.collection().caseId(), input.collection().contextId(),
                    input.collection().analysisId(), input.collection().runId(),
                    input.collection().planId(), input.collection().collectionId(),
                    input.rawTrace(), methodFacts, pathFacts, anomalyFacts,
                    scopeFact, summaryTruncated, input.createdAt());
            long emitted = (long) methodFacts.size() + pathFacts.size() + anomalyFacts.size();
            if (scopeFact.isPresent()) {
                emitted += 1L + scopeFact.orElseThrow().invocations().size()
                        + scopeFact.orElseThrow().pathVariants().size();
            }
            return new NormalizationResult<>(
                    partial ? NormalizationStatus.PARTIAL : NormalizationStatus.COMPLETE,
                    Optional.of(summary), recordCount, emitted, List.copyOf(reasons),
                    Optional.empty(), "");
        }

        private Optional<MethodPathSummary.ScopeSummary> buildScopeSummary() {
            if (scopeMethodKey.isEmpty()) {
                return Optional.empty();
            }
            if (scopeInvocations.isEmpty()) {
                reasons.add("SCOPE_NOT_OBSERVED");
            }
            Map<List<String>, List<MutableScopeInvocation>> grouped = new LinkedHashMap<>();
            scopeInvocations.stream().filter(MutableScopeInvocation::complete).forEach(invocation ->
                    grouped.computeIfAbsent(List.copyOf(invocation.methodSequence),
                            ignored -> new ArrayList<>()).add(invocation));
            List<MethodPathSummary.PathVariant> variants = new ArrayList<>();
            int variantNumber = 1;
            for (Map.Entry<List<String>, List<MutableScopeInvocation>> entry : grouped.entrySet()) {
                String pathId = "PATH_%03d".formatted(variantNumber++);
                entry.getValue().forEach(invocation -> invocation.pathId = pathId);
                variants.add(new MethodPathSummary.PathVariant(
                        pathId,
                        entry.getValue().stream().map(value -> value.ordinal).toList(),
                        entry.getKey()));
            }
            List<MethodPathSummary.ScopeInvocation> invocations = scopeInvocations.stream()
                    .map(MutableScopeInvocation::toFact).toList();
            int complete = Math.toIntExact(scopeInvocations.stream()
                    .filter(MutableScopeInvocation::complete).count());
            MethodPathSummary.ScopeSummary scope = new MethodPathSummary.ScopeSummary(
                    scopeMethodKey.orElseThrow(), scopeInvocations.size(), complete,
                    scopeInvocations.size() - complete, invocations, List.copyOf(variants));
            if (!summaryBudget.reserve(summaryBudget.scopeBytes(scope))) {
                return Optional.empty();
            }
            return Optional.of(scope);
        }

        private TraceProvenance provenance(long line, long eventId) {
            return new TraceProvenance(
                    input.collection().caseId(), input.collection().contextId(),
                    input.collection().runId(), input.collection().collectionId(),
                    input.rawTrace(), line, Optional.of(eventId), Optional.empty(),
                    "RAW_OBSERVATION");
        }
    }

    private static final class MutableMethod {
        private final String methodKey;
        private final boolean emitted;
        private long enterCount;
        private long exitCount;
        private int minDepth = Integer.MAX_VALUE;
        private int maxDepth;
        private TraceProvenance first;
        private TraceProvenance last;

        private MutableMethod(String methodKey, boolean emitted) {
            this.methodKey = methodKey;
            this.emitted = emitted;
        }

        private void observe(Event event, TraceProvenance provenance) {
            if ("METHOD_ENTER".equals(event.eventType)) enterCount++; else exitCount++;
            minDepth = Math.min(minDepth, event.depth);
            maxDepth = Math.max(maxDepth, event.depth);
            if (first == null) first = provenance;
            last = provenance;
        }

        private MethodPathSummary.MethodStatistic toFact() {
            return new MethodPathSummary.MethodStatistic(
                    methodKey, enterCount, exitCount, minDepth, maxDepth, first, last);
        }
    }

    private record PathKey(String ancestor, String descendant) {}

    private static final class MutablePath {
        private final PathKey key;
        private final TraceProvenance first;
        private long count = 1;

        private MutablePath(PathKey key, TraceProvenance first) {
            this.key = key;
            this.first = first;
        }

        private MethodPathSummary.ObservedPath toFact() {
            return new MethodPathSummary.ObservedPath(
                    key.ancestor, key.descendant,
                    "NEAREST_SELECTED_ANCESTOR", count, first);
        }
    }

    private static final class MutableScopeInvocation {
        private final int ordinal;
        private final long startEventId;
        private final int startDepth;
        private final List<String> methodSequence = new ArrayList<>();
        private long endEventId;
        private int eventCount;
        private int maxDepth;
        private String pathId;

        private MutableScopeInvocation(int ordinal, long startEventId, int startDepth) {
            this.ordinal = ordinal;
            this.startEventId = startEventId;
            this.startDepth = startDepth;
            this.maxDepth = startDepth;
        }

        private void observe(Event event, String methodKey) {
            eventCount++;
            maxDepth = Math.max(maxDepth, event.depth);
            if ("METHOD_ENTER".equals(event.eventType)) {
                methodSequence.add(methodKey);
            }
        }

        private boolean complete() {
            return endEventId > 0;
        }

        private MethodPathSummary.ScopeInvocation toFact() {
            return new MethodPathSummary.ScopeInvocation(
                    ordinal, startEventId,
                    complete() ? Optional.of(endEventId) : Optional.empty(),
                    eventCount, maxDepth,
                    complete() ? Optional.of(pathId) : Optional.empty(),
                    !complete());
        }
    }

    private record OpenCall(String methodKey, int depth, TraceProvenance provenance) {}

    /**
     * 以 JSON 的字段开销和实际 UTF-8 字符长度做保守计量，避免先构造无界摘要再序列化。
     * 该预算只限制结构规模，不查看或过滤字段语义。
     */
    private static final class SummaryBudget {
        private static final long SUMMARY_OVERHEAD = 1_536;
        private static final long PROVENANCE_OVERHEAD = 384;
        private static final long METHOD_OVERHEAD = 320;
        private static final long PATH_OVERHEAD = 320;
        private static final long ANOMALY_OVERHEAD = 256;
        private static final long SCOPE_OVERHEAD = 512;

        private final long maximum;
        private final LinkedHashSet<String> reasons;
        private final long provenanceBytes;
        private long reserved;
        private boolean exhausted;

        private SummaryBudget(
                CodePathNormalizationInput input,
                LinkedHashSet<String> reasons) {
            this.maximum = input.budget().maxSummaryBytes();
            this.reasons = reasons;
            this.provenanceBytes = PROVENANCE_OVERHEAD
                    + identityBytes(input)
                    + artifactBytes(input.rawTrace())
                    + jsonTextBytes("RAW_OBSERVATION");
            this.reserved = SUMMARY_OVERHEAD
                    + identityBytes(input)
                    + artifactBytes(input.rawTrace())
                    ;
            if (reserved > maximum) {
                throw new NormalizationException(
                        "NORMALIZE_OUTPUT_BUDGET_TOO_SMALL",
                        "The summary budget cannot preserve the evidence identity and raw artifact references", 0, null);
            }
        }

        private boolean reserve(long bytes) {
            if (exhausted) return false;
            if (bytes > maximum - reserved) {
                markExhausted();
                return false;
            }
            reserved += bytes;
            return true;
        }

        private long methodBytes(String methodKey) {
            return METHOD_OVERHEAD + jsonTextBytes(methodKey) + provenanceBytes * 2;
        }

        private long pathBytes(String ancestor, String descendant) {
            return PATH_OVERHEAD + provenanceBytes
                    + jsonTextBytes(ancestor)
                    + jsonTextBytes(descendant)
                    + jsonTextBytes("NEAREST_SELECTED_ANCESTOR");
        }

        private long anomalyBytes(String code, String detail) {
            return ANOMALY_OVERHEAD + provenanceBytes
                    + jsonTextBytes(code) + jsonTextBytes(detail);
        }

        private long scopeBytes(MethodPathSummary.ScopeSummary scope) {
            long bytes = SCOPE_OVERHEAD + jsonTextBytes(scope.methodKey());
            for (MethodPathSummary.ScopeInvocation invocation : scope.invocations()) {
                bytes += 256 + invocation.pathId().map(SummaryBudget::jsonTextBytes).orElse(0L);
            }
            for (MethodPathSummary.PathVariant variant : scope.pathVariants()) {
                bytes += 256 + jsonTextBytes(variant.pathId());
                for (String methodKey : variant.representativeMethodSequence()) {
                    bytes += jsonTextBytes(methodKey);
                }
                bytes += 16L * variant.invocationOrdinals().size();
            }
            return bytes;
        }

        private void markExhausted() {
            exhausted = true;
            reasons.add("OUTPUT_BUDGET_EXCEEDED");
        }

        private static long identityBytes(CodePathNormalizationInput input) {
            return jsonTextBytes(input.evidenceId().value())
                    + jsonTextBytes(input.collection().caseId().value())
                    + jsonTextBytes(input.collection().contextId().value())
                    + jsonTextBytes(input.collection().analysisId().value())
                    + jsonTextBytes(input.collection().runId().value())
                    + jsonTextBytes(input.collection().planId().value())
                    + jsonTextBytes(input.collection().collectionId().value());
        }

        private static long artifactBytes(org.example.algorithmdebug.contracts.ArtifactReference value) {
            return 192L
                    + jsonTextBytes(value.artifactId())
                    + jsonTextBytes(value.artifactType())
                    + jsonTextBytes(value.relativePath())
                    + jsonTextBytes(value.mediaType())
                    + jsonTextBytes(value.sha256());
        }

        private static long jsonTextBytes(String value) {
            long bytes = value.getBytes(StandardCharsets.UTF_8).length + 2L;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '"' || character == '\\') bytes++;
                else if (character <= 0x1f) bytes += 5L;
            }
            return bytes;
        }
    }

    private record Event(
            long eventId, String eventType, int depth,
            String className, String methodName, String descriptor) {

        private static Event parse(JsonNode json, long line) {
            long eventId = requiredLong(json, "eventId", line);
            int depth = requiredInt(json, "depth", line);
            String eventType = requiredText(json, "eventType", 64, line);
            String className = requiredText(json, "className", 1_024, line);
            String methodName = requiredText(json, "methodName", 512, line);
            String descriptor = requiredText(json, "descriptor", 512, line);
            if (eventId < 1 || depth < 0 || depth > 1_000_000
                    || !("METHOD_ENTER".equals(eventType) || "METHOD_EXIT".equals(eventType))) {
                throw invalid(line, "The CodePath event fields are invalid");
            }
            return new Event(eventId, eventType, depth,
                    className, methodName, descriptor);
        }

        private static long requiredLong(JsonNode json, String field, long line) {
            JsonNode value = json.get(field);
            if (value == null || !value.isIntegralNumber()) throw invalid(line, field + " is invalid");
            return value.longValue();
        }

        private static int requiredInt(JsonNode json, String field, long line) {
            long value = requiredLong(json, field, line);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid(line, field + " exceeds the limit");
            return (int) value;
        }

        private static String requiredText(JsonNode json, String field, int max, long line) {
            JsonNode value = json.get(field);
            if (value == null || !value.isTextual() || value.textValue().isBlank()
                    || value.textValue().length() > max) {
                throw invalid(line, field + " is invalid");
            }
            return value.textValue();
        }

        private static NormalizationException invalid(long line, String detail) {
            return new NormalizationException("NORMALIZE_SCHEMA_UNSUPPORTED", detail, line, null);
        }
    }
}
