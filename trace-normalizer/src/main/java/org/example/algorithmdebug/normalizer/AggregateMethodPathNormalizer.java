package org.example.algorithmdebug.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathProjection;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TraceProvenance;

/** 将 Launcher 的单个 AGGREGATE Raw 文档转换为通用方法路径摘要。 */
public final class AggregateMethodPathNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 读取并验证有界 Aggregate，不生成逐调用 Invocation 文件。 */
    public NormalizationResult<MethodPathSummary> normalize(CodePathNormalizationInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        try {
            long size = Files.size(input.rawTracePath());
            if (size < 1 || size > input.budget().maxRawBytes()) {
                throw invalid("NORMALIZE_RAW_SIZE_INVALID", "Aggregate Raw size is outside the budget");
            }
            JsonNode root = JSON.readTree(input.rawTracePath().toFile());
            requireText(root, "schemaVersion", "1.0");
            requireText(root, "captureMode", "AGGREGATE");
            requireText(root, "depthSemantics", "SELECTED_METHOD_DEPTH");
            LinkedHashSet<String> reasons = limitations(root.path("limitations"));
            if (input.collectorTruncated()) reasons.add("COLLECTOR_TRUNCATED");
            Map<String, MethodSelector> methodsInPlan = new HashMap<>();
            Map<ProjectionKey, CodePathProjection> projectionsInPlan = new HashMap<>();
            input.plan().methodSelections().forEach(selection -> {
                methodsInPlan.put(selection.selector().methodKey(), selection.selector());
                selection.projections().forEach(projection -> projectionsInPlan.put(
                        new ProjectionKey(selection.selector().methodKey(), projection.name()), projection));
            });
            List<MethodPathSummary.MethodStatistic> methods = methods(
                    root.path("methods"), methodsInPlan, input);
            if (methods.stream().anyMatch(method -> method.enterCount() != method.exitCount())) {
                reasons.add("TRACE_STRUCTURE_INCOMPLETE");
            }
            List<MethodPathSummary.ObservedPath> paths = paths(
                    root.path("observedPaths"), methodsInPlan.keySet(), input);
            List<MethodPathSummary.ProjectionDistribution> distributions = distributions(
                    root.path("projectionDistributions"), projectionsInPlan);
            Optional<MethodPathSummary.ScopeSummary> scope = scope(root.path("scope"), input);
            if (methods.isEmpty()) reasons.add("ZERO_RETAINED_EVENTS");
            if (distributions.stream().anyMatch(
                    MethodPathSummary.ProjectionDistribution::distinctLimitReached)) {
                reasons.add("VALUE_CARDINALITY_LIMIT_REACHED");
                reasons.add("TRACKED_VALUES_ARE_NOT_GLOBAL_TOP_VALUES");
            }
            EvidenceSourceCoverage coverage = reasons.isEmpty()
                    ? EvidenceSourceCoverage.COMPLETE : EvidenceSourceCoverage.PARTIAL;
            MethodPathSummary summary = new MethodPathSummary(
                    SchemaVersions.METHOD_PATH_SUMMARY, input.evidenceId(),
                    input.collection().caseId(), input.collection().analysisId(),
                    input.collection().runId(), input.collection().planId(),
                    input.collection().collectionId(), input.rawTrace(), methods, paths,
                    List.of(), scope, CodePathCaptureMode.AGGREGATE,
                    "SELECTED_METHOD_DEPTH", coverage, List.copyOf(reasons), distributions,
                    !reasons.isEmpty(), input.createdAt());
            long emitted = (long) methods.size() + paths.size() + distributions.size()
                    + distributions.stream().mapToLong(value -> value.trackedValueCounts().size()).sum()
                    + (scope.isPresent() ? 1 : 0);
            long records = requiredLong(root, "acceptedEvents");
            return new NormalizationResult<>(
                    reasons.isEmpty() ? NormalizationStatus.COMPLETE : NormalizationStatus.PARTIAL,
                    Optional.of(summary), records, emitted, List.copyOf(reasons),
                    Optional.empty(), "");
        } catch (NormalizationException failure) {
            return new NormalizationResult<>(
                    NormalizationStatus.FAILED, Optional.empty(), 0, 0, List.of(),
                    Optional.of(failure.code()), bounded(failure.getMessage()));
        } catch (IOException | RuntimeException failure) {
            return new NormalizationResult<>(
                    NormalizationStatus.FAILED, Optional.empty(), 0, 0, List.of(),
                    Optional.of("NORMALIZE_AGGREGATE_INVALID"), bounded(failure.toString()));
        }
    }

    private List<MethodPathSummary.MethodStatistic> methods(
            JsonNode values,
            Map<String, MethodSelector> methodsInPlan,
            CodePathNormalizationInput input) {
        requireArray(values, "methods", input.budget().maxMethods());
        List<MethodPathSummary.MethodStatistic> result = new ArrayList<>();
        for (JsonNode value : values) {
            String methodKey = requiredText(value, "methodKey");
            if (!methodsInPlan.containsKey(methodKey)) {
                throw invalid("NORMALIZE_EVENT_OUTSIDE_PLAN", "Aggregate method is outside the Plan");
            }
            long enter = requiredLong(value, "enterCount");
            long exits = Math.addExact(requiredLong(value, "normalExitCount"),
                    requiredLong(value, "exceptionalExitCount"));
            int minimum = requiredInt(value, "minSelectedDepth");
            int maximum = requiredInt(value, "maxSelectedDepth");
            long first = requiredLong(value, "firstEventId");
            long last = requiredLong(value, "lastEventId");
            result.add(new MethodPathSummary.MethodStatistic(
                    methodKey, enter, exits, minimum, maximum,
                    provenance(input, first), provenance(input, last)));
        }
        return result.stream().sorted(Comparator.comparing(
                MethodPathSummary.MethodStatistic::methodKey)).toList();
    }

    private List<MethodPathSummary.ObservedPath> paths(
            JsonNode values,
            Set<String> methodsInPlan,
            CodePathNormalizationInput input) {
        requireArray(values, "observedPaths", input.budget().maxRelationships());
        List<MethodPathSummary.ObservedPath> result = new ArrayList<>();
        for (JsonNode value : values) {
            String ancestor = requiredText(value, "ancestorMethodKey");
            String descendant = requiredText(value, "descendantMethodKey");
            if (!methodsInPlan.contains(ancestor) || !methodsInPlan.contains(descendant)) {
                throw invalid("NORMALIZE_EVENT_OUTSIDE_PLAN", "Aggregate path is outside the Plan");
            }
            result.add(new MethodPathSummary.ObservedPath(
                    ancestor, descendant, "NEAREST_SELECTED_ANCESTOR",
                    positiveLong(value, "count"),
                    provenance(input, positiveLong(value, "firstEventId"))));
        }
        return result.stream().sorted(Comparator.comparing(
                MethodPathSummary.ObservedPath::ancestorMethodKey).thenComparing(
                MethodPathSummary.ObservedPath::descendantMethodKey)).toList();
    }

    private List<MethodPathSummary.ProjectionDistribution> distributions(
            JsonNode values,
            Map<ProjectionKey, CodePathProjection> projectionsInPlan) {
        requireArray(values, "projectionDistributions", 1_600);
        List<MethodPathSummary.ProjectionDistribution> result = new ArrayList<>();
        for (JsonNode value : values) {
            String methodKey = requiredText(value, "methodKey");
            String name = requiredText(value, "projectionName");
            CodePathProjection projection = projectionsInPlan.get(new ProjectionKey(methodKey, name));
            if (projection == null) {
                throw invalid("NORMALIZE_PROJECTION_OUTSIDE_PLAN", "Aggregate projection is outside the Plan");
            }
            String path = requiredText(value, "path");
            if (!path.equals(path(projection))) {
                throw invalid("NORMALIZE_PROJECTION_PATH_MISMATCH", "Aggregate projection path differs from the Plan");
            }
            JsonNode counts = value.path("trackedValueCounts");
            requireArray(counts, "trackedValueCounts", 256);
            List<MethodPathSummary.TrackedValueCount> tracked = new ArrayList<>();
            for (JsonNode count : counts) {
                tracked.add(new MethodPathSummary.TrackedValueCount(
                        requiredText(count, "status"), requiredText(count, "scalarType"),
                        optionalText(count.get("value")), optionalText(count.get("failureCode")),
                        positiveLong(count, "count")));
            }
            result.add(new MethodPathSummary.ProjectionDistribution(
                    methodKey, name, path, requiredLong(value, "observedCount"),
                    requiredLong(value, "otherCount"), requiredBoolean(value, "distinctLimitReached"),
                    List.copyOf(tracked)));
        }
        return result.stream().sorted(Comparator.comparing(
                MethodPathSummary.ProjectionDistribution::methodKey).thenComparing(
                MethodPathSummary.ProjectionDistribution::projectionName)).toList();
    }

    private Optional<MethodPathSummary.ScopeSummary> scope(
            JsonNode value, CodePathNormalizationInput input) {
        if (!value.path("enabled").asBoolean(false)) return Optional.empty();
        String methodKey = requiredText(value, "methodKey");
        if (input.plan().scopeMethodKey().filter(methodKey::equals).isEmpty()) {
            throw invalid("NORMALIZE_SCOPE_IDENTITY_MISMATCH", "Aggregate Scope differs from the Plan");
        }
        int captured = Math.toIntExact(requiredLong(value, "capturedInvocations"));
        int incomplete = Math.toIntExact(requiredLong(value, "incompleteCapturedInvocations"));
        if (incomplete > captured) {
            throw invalid("NORMALIZE_SCOPE_COUNTS_INVALID", "Aggregate Scope counts are invalid");
        }
        return Optional.of(new MethodPathSummary.ScopeSummary(
                methodKey, captured, captured - incomplete, incomplete, List.of(), List.of()));
    }

    private LinkedHashSet<String> limitations(JsonNode values) {
        requireArray(values, "limitations", 32);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 256) {
                throw invalid("NORMALIZE_LIMITATION_INVALID", "Aggregate limitation is invalid");
            }
            result.add(value.textValue());
        });
        return result;
    }

    private TraceProvenance provenance(CodePathNormalizationInput input, long eventId) {
        return new TraceProvenance(
                input.collection().caseId(), input.collection().runId(),
                input.collection().collectionId(), input.rawTrace(), 1,
                Optional.empty(), Optional.empty(), "RAW_AGGREGATE_OBSERVATION");
    }

    private static String path(CodePathProjection projection) {
        String root = projection.source()
                == org.example.algorithmdebug.contracts.CodePathProjectionSource.ARGUMENT
                ? "arg[" + projection.argumentIndex().orElseThrow() + "]" : "return";
        return projection.fieldPath().isEmpty() ? root : root + "." + String.join(".", projection.fieldPath());
    }

    private static void requireArray(JsonNode value, String field, long maximum) {
        if (!value.isArray() || value.size() > maximum) {
            throw invalid("NORMALIZE_AGGREGATE_SCHEMA_INVALID", field + " is invalid");
        }
    }
    private static void requireText(JsonNode value, String field, String expected) {
        if (!expected.equals(value.path(field).asText())) {
            throw invalid("NORMALIZE_AGGREGATE_SCHEMA_INVALID", field + " is invalid");
        }
    }
    private static String requiredText(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw invalid("NORMALIZE_AGGREGATE_SCHEMA_INVALID", field + " is invalid");
        }
        return node.textValue();
    }
    private static long requiredLong(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || node.longValue() < 0) {
            throw invalid("NORMALIZE_AGGREGATE_SCHEMA_INVALID", field + " is invalid");
        }
        return node.longValue();
    }
    private static long positiveLong(JsonNode value, String field) {
        long result = requiredLong(value, field);
        if (result < 1) throw invalid("NORMALIZE_AGGREGATE_SCHEMA_INVALID", field + " must be positive");
        return result;
    }
    private static int requiredInt(JsonNode value, String field) {
        return Math.toIntExact(requiredLong(value, field));
    }
    private static boolean requiredBoolean(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isBoolean()) {
            throw invalid("NORMALIZE_AGGREGATE_SCHEMA_INVALID", field + " is invalid");
        }
        return node.booleanValue();
    }
    private static Optional<String> optionalText(JsonNode value) {
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual()) throw invalid("NORMALIZE_AGGREGATE_SCHEMA_INVALID", "Optional text is invalid");
        return Optional.of(value.textValue());
    }
    private static NormalizationException invalid(String code, String detail) {
        return new NormalizationException(code, detail, 1, null);
    }
    private static String bounded(String detail) {
        return detail.length() <= 2_048 ? detail : detail.substring(0, 2_048);
    }
    private record ProjectionKey(String methodKey, String projectionName) {}
}
