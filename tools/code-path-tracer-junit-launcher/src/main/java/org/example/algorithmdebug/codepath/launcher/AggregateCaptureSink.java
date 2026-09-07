package org.example.algorithmdebug.codepath.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** AGGREGATE 模式的单线程有界计数器，在关闭时只提交一个 JSON 文档。 */
final class AggregateCaptureSink implements CodePathCaptureSink {
    static final int MAX_DISTINCT_VALUES_PER_PROJECTION = 256;
    static final int MAX_TOTAL_DISTINCT_VALUES = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path output;
    private final long maxEvents;
    private final long maxBytes;
    private final int maxDistinctValues;
    private final Map<String, MutableMethod> methods = new LinkedHashMap<>();
    private final Map<Edge, MutableEdge> edges = new LinkedHashMap<>();
    private final Map<ProjectionKey, MutableProjection> projections = new LinkedHashMap<>();
    private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
    private long sequence;
    private long acceptedEvents;
    private int distinctValues;
    private boolean closed;
    private ScopeCaptureStatistics scopeStatistics = new ScopeCaptureStatistics(
            false, null, 0, 0, 0, 0, 0, 0);

    AggregateCaptureSink(Path output, LauncherCodePathPlan.Budget budget) {
        this.output = output.toAbsolutePath().normalize();
        this.maxEvents = budget.maxEvents();
        this.maxBytes = budget.maxBytes();
        this.maxDistinctValues = Math.min(MAX_TOTAL_DISTINCT_VALUES,
                Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0, Math.min(maxBytes, 4L * 1024 * 1024) - 32_768) / 1_024)));
    }

    @Override
    public long enter(String methodKey, int selectedDepth, String parentMethodKey, Long parentEnterEventId,
            List<ProjectionValue> values) {
        long eventId = ++sequence;
        if (!acceptEvent()) return eventId;
        methods.computeIfAbsent(methodKey, MutableMethod::new).enter(eventId, selectedDepth);
        if (parentMethodKey != null) {
            Edge edge = new Edge(parentMethodKey, methodKey);
            edges.computeIfAbsent(edge, ignored -> new MutableEdge(eventId)).count++;
        }
        observe(methodKey, values);
        return eventId;
    }

    @Override
    public void exit(String methodKey, int selectedDepth, long enterEventId,
            List<ProjectionValue> values, Throwable thrown) {
        if (!acceptEvent()) return;
        MutableMethod method = methods.computeIfAbsent(methodKey, MutableMethod::new);
        method.exit(++sequence, selectedDepth, thrown != null);
        observe(methodKey, values);
    }

    private boolean acceptEvent() {
        if (acceptedEvents >= maxEvents) {
            reasons.add("EVENT_BUDGET_EXCEEDED");
            return false;
        }
        acceptedEvents++;
        if (acceptedEvents >= maxEvents) reasons.add("EVENT_BUDGET_EXCEEDED");
        return true;
    }

    private void observe(String methodKey, List<ProjectionValue> values) {
        for (ProjectionValue value : values) {
            ProjectionKey key = new ProjectionKey(methodKey, value.name(), value.path());
            MutableProjection projection = projections.computeIfAbsent(
                    key, ignored -> new MutableProjection(key));
            ValueKey valueKey = ValueKey.of(value);
            projection.observedCount++;
            Long count = projection.values.get(valueKey);
            if (count != null) {
                projection.values.put(valueKey, count + 1);
            } else if (projection.values.size() < MAX_DISTINCT_VALUES_PER_PROJECTION
                    && distinctValues < maxDistinctValues) {
                projection.values.put(valueKey, 1L);
                distinctValues++;
            } else {
                projection.otherCount++;
                projection.distinctLimitReached = true;
                reasons.add("VALUE_CARDINALITY_LIMIT_REACHED");
                reasons.add("TRACKED_VALUES_ARE_NOT_GLOBAL_TOP_VALUES");
            }
        }
    }

    @Override public boolean stopped() { return acceptedEvents >= maxEvents; }
    @Override public TraceJsonlSink.Limit limit() {
        return reasons.contains("EVENT_BUDGET_EXCEEDED")
                ? TraceJsonlSink.Limit.EVENTS : TraceJsonlSink.Limit.NONE;
    }
    @Override public long eventsWritten() { return acceptedEvents; }
    @Override public long bytesWritten() {
        try { return Files.exists(output) ? Files.size(output) : 0; }
        catch (IOException failure) { return 0; }
    }
    @Override public List<String> reasons() { return List.copyOf(reasons); }
    @Override public void runtimeReasons(List<String> values) { reasons.addAll(values); }
    @Override public void scopeStatistics(ScopeCaptureStatistics statistics) {
        scopeStatistics = statistics;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        ObjectNode document = document(true, true);
        byte[] bytes = JSON.writeValueAsBytes(document);
        if (bytes.length > maxBytes) {
            reasons.add("OUTPUT_BUDGET_EXCEEDED");
            document = document(false, true);
            bytes = JSON.writeValueAsBytes(document);
        }
        if (bytes.length > maxBytes) {
            document = document(false, false);
            bytes = JSON.writeValueAsBytes(document);
        }
        if (bytes.length > maxBytes) {
            throw new IOException("The aggregate CodePath document exceeds maxBytes");
        }
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, output);
        }
    }

    private ObjectNode document(boolean includeValues, boolean includeEdges) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("captureMode", "AGGREGATE");
        root.put("depthSemantics", "SELECTED_METHOD_DEPTH");
        root.put("acceptedEvents", acceptedEvents);
        ArrayNode methodArray = root.putArray("methods");
        methods.values().stream().sorted(Comparator.comparing(value -> value.methodKey))
                .forEach(value -> value.write(methodArray.addObject()));
        ArrayNode edgeArray = root.putArray("observedPaths");
        if (includeEdges) edges.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Edge::ancestor)
                        .thenComparing(Edge::descendant)))
                .forEach(entry -> edgeArray.addObject()
                        .put("ancestorMethodKey", entry.getKey().ancestor)
                        .put("descendantMethodKey", entry.getKey().descendant)
                        .put("count", entry.getValue().count)
                        .put("firstEventId", entry.getValue().firstEventId));
        ArrayNode projectionArray = root.putArray("projectionDistributions");
        projections.values().stream().sorted(Comparator.comparing(
                        (MutableProjection value) -> value.key.methodKey)
                        .thenComparing(value -> value.key.projectionName))
                .forEach(value -> value.write(projectionArray.addObject(), includeValues));
        ObjectNode scope = root.putObject("scope");
        scope.put("enabled", scopeStatistics.enabled());
        if (scopeStatistics.methodKey() == null) scope.putNull("methodKey");
        else scope.put("methodKey", scopeStatistics.methodKey());
        scope.put("observedInvocations", scopeStatistics.observed());
        scope.put("matchedInvocations", scopeStatistics.matched());
        scope.put("capturedInvocations", scopeStatistics.captured());
        scope.put("skippedByWindowInvocations", scopeStatistics.skippedByWindow());
        scope.put("unavailableConditionInvocations", scopeStatistics.unavailable());
        scope.put("incompleteCapturedInvocations", scopeStatistics.incompleteCaptured());
        ArrayNode reasonArray = root.putArray("limitations");
        reasons.forEach(reasonArray::add);
        return root;
    }

    private static final class MutableMethod {
        private final String methodKey;
        private long enterCount;
        private long normalExitCount;
        private long exceptionalExitCount;
        private int minDepth = Integer.MAX_VALUE;
        private int maxDepth;
        private long firstEventId;
        private long lastEventId;
        private MutableMethod(String methodKey) { this.methodKey = methodKey; }
        private void enter(long eventId, int depth) {
            enterCount++; observe(eventId, depth);
        }
        private void exit(long eventId, int depth, boolean exceptional) {
            if (exceptional) exceptionalExitCount++; else normalExitCount++;
            observe(eventId, depth);
        }
        private void observe(long eventId, int depth) {
            if (firstEventId == 0) firstEventId = eventId;
            lastEventId = eventId;
            minDepth = Math.min(minDepth, depth);
            maxDepth = Math.max(maxDepth, depth);
        }
        private void write(ObjectNode node) {
            node.put("methodKey", methodKey).put("enterCount", enterCount)
                    .put("normalExitCount", normalExitCount)
                    .put("exceptionalExitCount", exceptionalExitCount)
                    .put("minSelectedDepth", minDepth == Integer.MAX_VALUE ? 0 : minDepth)
                    .put("maxSelectedDepth", maxDepth)
                    .put("firstEventId", firstEventId).put("lastEventId", lastEventId);
        }
    }

    private static final class MutableProjection {
        private final ProjectionKey key;
        private final Map<ValueKey, Long> values = new LinkedHashMap<>();
        private long observedCount;
        private long otherCount;
        private boolean distinctLimitReached;
        private MutableProjection(ProjectionKey key) { this.key = key; }
        private void write(ObjectNode node, boolean includeValues) {
            node.put("methodKey", key.methodKey).put("projectionName", key.projectionName)
                    .put("path", key.path).put("observedCount", observedCount)
                    .put("otherCount", includeValues ? otherCount : observedCount)
                    .put("distinctLimitReached", distinctLimitReached || !includeValues);
            ArrayNode counts = node.putArray("trackedValueCounts");
            if (includeValues) values.entrySet().stream()
                    .sorted(Map.Entry.<ValueKey, Long>comparingByValue().reversed()
                            .thenComparing(entry -> entry.getKey().sortKey()))
                    .forEach(entry -> entry.getKey().write(counts.addObject(), entry.getValue()));
        }
    }

    private record ProjectionKey(String methodKey, String projectionName, String path) {}
    private record Edge(String ancestor, String descendant) {}
    private static final class MutableEdge {
        private final long firstEventId;
        private long count;
        private MutableEdge(long firstEventId) { this.firstEventId = firstEventId; }
    }
    private record ValueKey(String status, String scalarType, String value, String failureCode) {
        static ValueKey of(ProjectionValue value) {
            Object scalar = value.value();
            String type = scalar == null ? "NONE" : scalar instanceof Boolean ? "BOOLEAN"
                    : scalar instanceof Number ? "NUMBER" : "STRING";
            return new ValueKey(value.status().name(), type,
                    scalar == null ? null : scalar.toString(), value.failureCode());
        }
        String sortKey() { return status + ':' + scalarType + ':' + value + ':' + failureCode; }
        void write(ObjectNode node, long count) {
            node.put("status", status).put("scalarType", scalarType);
            if (value == null) node.putNull("value"); else node.put("value", value);
            if (failureCode == null) node.putNull("failureCode"); else node.put("failureCode", failureCode);
            node.put("count", count);
        }
    }
}
