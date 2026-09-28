package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.function.Consumer;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceQueryFilter;
import org.example.algorithmdebug.contracts.EvidenceQueryGroupBy;
import org.example.algorithmdebug.contracts.EvidenceQueryMode;
import org.example.algorithmdebug.contracts.EvidenceQueryNextAction;
import org.example.algorithmdebug.contracts.EvidenceQueryOutcome;
import org.example.algorithmdebug.contracts.EvidenceQueryRequest;
import org.example.algorithmdebug.contracts.EvidenceQueryResult;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.EvidenceValuePredicate;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 查询已注册且完整性校验通过的 CodePath/JDWP 派生证据。
 * 只提供结构化筛选、窗口、聚合和变化提取，不解释算法业务含义，也不创建新 Artifact。
 */
public final class RegisteredEvidenceQuery {
    public static final int MAX_LIMIT = 50;
    public static final int MAX_OUTPUT_BYTES = 65_536;
    private static final int MAX_RECORD_BYTES = 1_048_576;
    private static final long MAX_RECORDS = 100_000;
    private static final int MAX_GROUPS = 20_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CaseArchiveRepository repository;
    private final CaseArtifactAccess access;
    private final BoundedDocumentMapper mapper;

    /** @param repository Case 归档和 Artifact 注册入口 */
    public RegisteredEvidenceQuery(CaseArchiveRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        this.repository = repository;
        this.access = new CaseArtifactAccess(repository.casesRoot());
        this.mapper = new BoundedDocumentMapper();
    }

    /** 兼容原有单条件 FILTER 调用。 */
    public EvidenceQueryResult query(
            CaseId caseId,
            String artifactId,
            EvidenceQueryFilter filter,
            int offset,
            int limit,
            int maxBytes) {
        return query(caseId, artifactId,
                EvidenceQueryRequest.filter(filter, List.of(), offset, limit, maxBytes));
    }

    /** 对受支持的已注册动态证据执行一个有界查询。 */
    public EvidenceQueryResult query(
            CaseId caseId,
            String artifactId,
            EvidenceQueryRequest request) {
        validateIdentity(caseId, artifactId, request);
        ArtifactReference artifact = repository.requireArtifactRegistration(
                caseId, artifactId).artifact();
        Path file = access.requireVerifiedArtifact(caseId, artifact);
        return switch (artifact.artifactType()) {
            case "CODEPATH_INVOCATIONS" -> queryCodePath(caseId, artifact, file, request);
            case "METHOD_PATH_SUMMARY" -> queryMethodPathSummary(artifact, file, request);
            case "JDWP_SNAPSHOT_SUMMARY" -> queryJdwp(caseId, artifact, file, request);
            default -> throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_ARTIFACT_UNSUPPORTED",
                    "Evidence Query supports CodePath invocations, Method Path summaries, and JDWP snapshot summaries");
        };
    }

    private EvidenceQueryResult queryMethodPathSummary(
            ArtifactReference artifact,
            Path file,
            EvidenceQueryRequest request) {
        JsonNode document = mapper.readJsonArtifact(file, JsonNode.class);
        EvidenceSourceCoverage coverage;
        try {
            coverage = EvidenceSourceCoverage.valueOf(
                    document.path("sourceCoverage").asText("UNKNOWN"));
        } catch (IllegalArgumentException failure) {
            coverage = EvidenceSourceCoverage.UNKNOWN;
        }
        List<String> limitations = textValues(document.path("limitations"), 32);
        if (request.mode() != EvidenceQueryMode.SUMMARY
                && request.mode() != EvidenceQueryMode.COUNT) {
            ObjectNode payload = JSON.createObjectNode();
            payload.put("message", "METHOD_PATH_SUMMARY supports only SUMMARY and COUNT");
            payload.putArray("supportedModes").add("SUMMARY").add("COUNT");
            return new EvidenceQueryResult(
                    SchemaVersions.EVIDENCE_QUERY_RESULT, artifact, "METHOD_PATH_SUMMARY",
                    request.mode(), EvidenceQueryOutcome.UNSUPPORTED_MODE, coverage, request,
                    0, 0, 1, false, limitations,
                    EvidenceQueryNextAction.REFINE_QUERY,
                    oneLine(payload, request.maxBytes()));
        }
        if (request.mode() == EvidenceQueryMode.SUMMARY) {
            return summarizeMethodPath(artifact, document, request, coverage, limitations);
        }
        return countMethodPath(artifact, document, request, coverage, limitations);
    }

    private EvidenceQueryResult summarizeMethodPath(
            ArtifactReference artifact,
            JsonNode document,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage,
            List<String> limitations) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("captureMode", document.path("captureMode").asText("TRACE"));
        payload.put("depthSemantics", document.path("depthSemantics").asText("SELECTED_METHOD_DEPTH"));
        payload.put("sourceCoverage", coverage.name());
        JsonNode methods = document.path("methods");
        JsonNode paths = document.path("observedPaths");
        JsonNode distributions = document.path("projectionDistributions");
        int methodCount = methods.isArray() ? methods.size() : 0;
        int distributionCount = distributions.isArray() ? distributions.size() : 0;
        payload.put("methodCount", methodCount);
        payload.put("observedPathCount", paths.isArray() ? paths.size() : 0);
        payload.put("projectionDistributionCount", distributionCount);
        payload.put("methodFactsOmitted", methodCount);
        payload.put("projectionFactsOmitted", distributionCount);
        ArrayNode payloadLimitations = payload.putArray("limitations");
        payload.put("limitationsOmitted", limitations.size());
        ArrayNode methodFacts = payload.putArray("methods");
        ArrayNode projectionFacts = payload.putArray("projections");
        for (String limitation : limitations) {
            payloadLimitations.add(limitation);
            payload.put("limitationsOmitted", limitations.size() - payloadLimitations.size());
            if (!fits(payload, request.maxBytes())) {
                payloadLimitations.remove(payloadLimitations.size() - 1);
                payload.put("limitationsOmitted", limitations.size() - payloadLimitations.size());
                break;
            }
        }
        if (methods.isArray()) for (int index = 0; index < Math.min(50, methods.size()); index++) {
            JsonNode method = methods.get(index);
            methodFacts.addObject().put("methodKey", method.path("methodKey").asText())
                    .put("enterCount", method.path("enterCount").asLong())
                    .put("exitCount", method.path("exitCount").asLong())
                    .put("minDepth", method.path("minDepth").asInt())
                    .put("maxDepth", method.path("maxDepth").asInt());
            payload.put("methodFactsOmitted", methodCount - methodFacts.size());
            if (!fits(payload, request.maxBytes())) {
                methodFacts.remove(methodFacts.size() - 1);
                payload.put("methodFactsOmitted", methodCount - methodFacts.size());
                break;
            }
        }
        if (distributions.isArray()) for (int index = 0; index < Math.min(50, distributions.size()); index++) {
            JsonNode value = distributions.get(index);
            projectionFacts.addObject().put("methodKey", value.path("methodKey").asText())
                    .put("projectionName", value.path("projectionName").asText())
                    .put("path", value.path("path").asText())
                    .put("observedCount", value.path("observedCount").asLong())
                    .put("otherCount", value.path("otherCount").asLong())
                    .put("distinctLimitReached", value.path("distinctLimitReached").asBoolean());
            payload.put("projectionFactsOmitted", distributionCount - projectionFacts.size());
            if (!fits(payload, request.maxBytes())) {
                projectionFacts.remove(projectionFacts.size() - 1);
                payload.put("projectionFactsOmitted", distributionCount - projectionFacts.size());
                break;
            }
        }
        long observations = methods.isArray()
                ? java.util.stream.StreamSupport.stream(methods.spliterator(), false)
                        .mapToLong(value -> value.path("enterCount").asLong()).sum() : 0;
        List<String> resultLimitations = new ArrayList<>(limitations);
        if (payload.path("methodFactsOmitted").asInt() > 0
                || payload.path("projectionFactsOmitted").asInt() > 0
                || payload.path("limitationsOmitted").asInt() > 0) {
            resultLimitations.add("SUMMARY_DETAILS_OMITTED_BY_QUERY_BUDGET");
        }
        return result(artifact, "METHOD_PATH_SUMMARY", request, coverage,
                observations, observations, 1, false,
                List.copyOf(new LinkedHashSet<>(resultLimitations)),
                oneLine(payload, request.maxBytes()));
    }

    private EvidenceQueryResult countMethodPath(
            ArtifactReference artifact,
            JsonNode document,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage,
            List<String> sourceLimitations) {
        if (request.groupBy().isEmpty()
                || request.groupBy().orElseThrow() == EvidenceQueryGroupBy.TRACEPOINT_ID
                || request.filter().tracepointId().isPresent()
                || request.filter().sequenceFrom().isPresent()
                || request.filter().sequenceTo().isPresent()
                || !request.predicates().isEmpty()) {
            ObjectNode payload = JSON.createObjectNode();
            payload.put("message", "The requested COUNT dimensions are unavailable in METHOD_PATH_SUMMARY");
            payload.putArray("supportedGroupBy").add("METHOD_REF")
                    .add("PROJECTION_VALUE").add("VALUE_STATUS");
            return new EvidenceQueryResult(
                    SchemaVersions.EVIDENCE_QUERY_RESULT, artifact, "METHOD_PATH_SUMMARY",
                    request.mode(), EvidenceQueryOutcome.UNSUPPORTED_MODE, coverage, request,
                    0, 0, 1, false, sourceLimitations,
                    EvidenceQueryNextAction.REFINE_QUERY,
                    oneLine(payload, request.maxBytes()));
        }
        Map<String, Long> groups = new HashMap<>();
        long observed = 0;
        long untrackedOther = 0;
        if (request.groupBy().orElseThrow() == EvidenceQueryGroupBy.METHOD_REF) {
            JsonNode methods = document.path("methods");
            if (methods.isArray()) for (JsonNode method : methods) {
                String methodKey = method.path("methodKey").asText("");
                if (!matchesText(method.path("methodKey"), request.filter().methodRef())) continue;
                long count = method.path("enterCount").asLong();
                groups.merge(methodKey, count, Long::sum);
                observed += count;
            }
        } else {
            JsonNode distributions = document.path("projectionDistributions");
            if (distributions.isArray()) for (JsonNode distribution : distributions) {
                if (!distribution.path("projectionName").asText("")
                        .equals(request.groupValueName().orElseThrow())
                        || !matchesText(distribution.path("methodKey"), request.filter().methodRef())) continue;
                for (JsonNode value : distribution.path("trackedValueCounts")) {
                    String key = request.groupBy().orElseThrow() == EvidenceQueryGroupBy.VALUE_STATUS
                            ? value.path("status").asText("MISSING")
                            : value.path("value").isNull()
                            ? "<" + value.path("status").asText("MISSING") + ">"
                            : value.path("value").asText();
                    long count = value.path("count").asLong();
                    groups.merge(key, count, Long::sum);
                    observed += count;
                }
                long other = distribution.path("otherCount").asLong();
                untrackedOther += other;
                observed += other;
            }
        }
        List<Map.Entry<String, Long>> sorted = sortedCounts(groups);
        ArrayNode rows = JSON.createArrayNode();
        long retained = 0;
        for (int index = 0; index < Math.min(request.topN(), sorted.size()); index++) {
            Map.Entry<String, Long> entry = sorted.get(index);
            rows.addObject().put("key", entry.getKey()).put("count", entry.getValue());
            retained += entry.getValue();
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("groupBy", request.groupBy().orElseThrow().name());
        request.groupValueName().ifPresent(value -> payload.put("groupValueName", value));
        payload.set("groups", rows);
        payload.put("otherCount", observed - retained);
        payload.put("untrackedOtherCount", untrackedOther);
        payload.put("trackedDistinctGroups", sorted.size());
        List<String> limitations = new ArrayList<>(sourceLimitations);
        if (untrackedOther > 0 && !limitations.contains("UNTRACKED_AGGREGATE_VALUES_PRESENT")) {
            limitations.add("UNTRACKED_AGGREGATE_VALUES_PRESENT");
        }
        return result(artifact, "METHOD_PATH_SUMMARY", request, coverage,
                observed, observed, 1, false, List.copyOf(limitations),
                oneLine(payload, request.maxBytes()));
    }

    private static List<String> textValues(JsonNode values, int maximum) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (result.size() >= maximum) break;
            if (value.isTextual() && !value.textValue().isBlank()) result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private EvidenceQueryResult queryCodePath(
            CaseId caseId,
            ArtifactReference artifact,
            Path file,
            EvidenceQueryRequest request) {
        if (request.filter().tracepointId().isPresent()
                || request.mode() == EvidenceQueryMode.CHANGES
                || request.groupBy().filter(value -> value == EvidenceQueryGroupBy.TRACEPOINT_ID).isPresent()) {
            throw new IllegalArgumentException(
                    "Tracepoint and CHANGES queries are not valid for CodePath invocations");
        }
        RecordScanner scanner = consumer -> scanCodePath(file, consumer);
        return execute(artifact, "CODEPATH_INVOCATION", true, scanner, request,
                collectionCoverage(caseId, artifact));
    }

    private EvidenceQueryResult queryJdwp(
            CaseId caseId,
            ArtifactReference artifact,
            Path file,
            EvidenceQueryRequest request) {
        if (request.filter().methodRef().isPresent()
                || request.groupBy().filter(value -> value == EvidenceQueryGroupBy.METHOD_REF).isPresent()) {
            throw new IllegalArgumentException("methodRef is not valid for JDWP snapshots");
        }
        JsonNode document = mapper.readJsonArtifact(file, JsonNode.class);
        JsonNode hits = document.path("hits");
        if (!hits.isArray()) {
            throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_SCHEMA_INVALID",
                    "JDWP summary does not contain a hits array");
        }
        RecordScanner scanner = consumer -> {
            long count = 0;
            for (JsonNode hit : hits) {
                if (++count > MAX_RECORDS) throw scanLimit();
                consumer.accept(hit);
            }
            return count;
        };
        EvidenceSourceCoverage coverage = document.path("truncated").asBoolean(false)
                || (document.path("limitations").isArray() && !document.path("limitations").isEmpty())
                ? EvidenceSourceCoverage.PARTIAL : collectionCoverage(caseId, artifact);
        return execute(artifact, "JDWP_SNAPSHOT", false, scanner, request, coverage);
    }

    private EvidenceQueryResult execute(
            ArtifactReference artifact,
            String recordType,
            boolean codePath,
            RecordScanner scanner,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage) {
        return switch (request.mode()) {
            case SUMMARY -> summarize(artifact, recordType, codePath, scanner, request, coverage);
            case FILTER -> filter(artifact, recordType, codePath, scanner, request, coverage);
            case WINDOW -> window(artifact, recordType, codePath, scanner, request, coverage);
            case COUNT -> count(artifact, recordType, codePath, scanner, request, coverage);
            case CHANGES -> changes(artifact, recordType, scanner, request, coverage);
        };
    }

    private EvidenceQueryResult summarize(
            ArtifactReference artifact,
            String recordType,
            boolean codePath,
            RecordScanner scanner,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage) {
        Map<String, Long> identities = new HashMap<>();
        Map<String, Long> names = new HashMap<>();
        Map<String, Long> statuses = new HashMap<>();
        long[] minimum = {Long.MAX_VALUE};
        long[] maximum = {Long.MIN_VALUE};
        long[] matched = {0};
        long scanned = scanner.scan(record -> {
            if (!matchesRecord(record, request, codePath)) return;
            matched[0]++;
            String identity = record.path(codePath ? "methodRef" : "tracepointId").asText("");
            increment(identities, identity);
            long sequence = sequence(record, codePath);
            if (sequence > 0) {
                minimum[0] = Math.min(minimum[0], sequence);
                maximum[0] = Math.max(maximum[0], sequence);
            }
            JsonNode values = record.path("projections");
            if (values.isArray()) for (JsonNode value : values) {
                increment(names, value.path(codePath ? "name" : "valuePath").asText(""));
                increment(statuses, value.path("status").asText(""));
            }
        });
        ObjectNode summary = JSON.createObjectNode();
        summary.put("recordCount", matched[0]);
        summary.set(codePath ? "methodRefs" : "tracepointIds", topCounts(identities, 50));
        summary.set("valueNames", topCounts(names, 50));
        summary.set("valueStatuses", topCounts(statuses, 50));
        if (minimum[0] != Long.MAX_VALUE) {
            summary.put("minimumSequence", minimum[0]);
            summary.put("maximumSequence", maximum[0]);
        }
        String output = oneLine(summary, request.maxBytes());
        return result(artifact, recordType, request, coverage, scanned, matched[0],
                1, false, List.of(), output);
    }

    private EvidenceQueryResult filter(
            ArtifactReference artifact,
            String recordType,
            boolean codePath,
            RecordScanner scanner,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage) {
        ResultAccumulator output = new ResultAccumulator(request);
        long scanned = scanner.scan(record -> {
            if (matchesRecord(record, request, codePath)) output.match(record);
        });
        return output.finish(artifact, recordType, coverage, scanned, List.of());
    }

    private EvidenceQueryResult window(
            ArtifactReference artifact,
            String recordType,
            boolean codePath,
            RecordScanner scanner,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage) {
        Deque<JsonNode> before = new ArrayDeque<>();
        List<JsonNode> selected = new ArrayList<>();
        boolean[] found = {false};
        int[] remainingAfter = {request.afterRecords()};
        long anchor = request.anchorSequence().orElseThrow();
        long scanned = scanner.scan(record -> {
            if (!matchesRecord(record, request, codePath)) return;
            if (!found[0]) {
                if (sequence(record, codePath) == anchor) {
                    found[0] = true;
                    selected.addAll(before);
                    selected.add(record);
                } else {
                    before.addLast(record);
                    if (before.size() > request.beforeRecords()) before.removeFirst();
                }
            } else if (remainingAfter[0] > 0) {
                selected.add(record);
                remainingAfter[0]--;
            }
        });
        String output = serializeRows(selected, request.maxBytes());
        return result(artifact, recordType, request, coverage, scanned, selected.size(),
                selected.size(), false, List.of(), output);
    }

    private EvidenceQueryResult count(
            ArtifactReference artifact,
            String recordType,
            boolean codePath,
            RecordScanner scanner,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage) {
        Map<String, Long> groups = new HashMap<>();
        long[] groupedRecords = {0};
        long scanned = scanner.scan(record -> {
            if (!matchesRecord(record, request, codePath)) return;
            Optional<String> key = groupKey(record, request, codePath);
            if (key.isEmpty()) return;
            if (!groups.containsKey(key.orElseThrow()) && groups.size() >= MAX_GROUPS) {
                throw new WorkspaceException(
                        "CASE_EVIDENCE_QUERY_GROUP_LIMIT_EXCEEDED",
                        "COUNT exceeds 20000 distinct groups; narrow the query first");
            }
            increment(groups, key.orElseThrow());
            groupedRecords[0]++;
        });
        List<Map.Entry<String, Long>> sorted = sortedCounts(groups);
        ArrayNode rows = JSON.createArrayNode();
        long retainedCount = 0;
        for (int index = 0; index < Math.min(request.topN(), sorted.size()); index++) {
            Map.Entry<String, Long> entry = sorted.get(index);
            rows.addObject().put("key", entry.getKey()).put("count", entry.getValue());
            retainedCount += entry.getValue();
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("groupBy", request.groupBy().orElseThrow().name());
        request.groupValueName().ifPresent(value -> payload.put("groupValueName", value));
        payload.set("groups", rows);
        payload.put("otherCount", groupedRecords[0] - retainedCount);
        payload.put("distinctGroups", sorted.size());
        return result(artifact, recordType, request, coverage, scanned, groupedRecords[0],
                1, false, List.of(), oneLine(payload, request.maxBytes()));
    }

    private EvidenceQueryResult changes(
            ArtifactReference artifact,
            String recordType,
            RecordScanner scanner,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage) {
        ResultAccumulator output = new ResultAccumulator(request);
        JsonNode[] previous = {null};
        boolean[] sampled = {false};
        long scanned = scanner.scan(record -> {
            if (!matchesRecord(record, request, false)) return;
            if (previous[0] != null) {
                long skipped = Math.max(0,
                        record.path("matchedHit").asLong()
                                - previous[0].path("matchedHit").asLong() - 1);
                if (skipped > 0) sampled[0] = true;
                ObjectNode change = change(previous[0], record, request.changeValueNames());
                if (!change.path("changes").isEmpty()) {
                    output.match(change);
                }
            }
            previous[0] = record;
        });
        List<String> limitations = sampled[0]
                ? List.of("SAMPLED_CHANGES_DO_NOT_PROVE_INTERMEDIATE_STATES") : List.of();
        EvidenceSourceCoverage effectiveCoverage = sampled[0]
                ? EvidenceSourceCoverage.PARTIAL : coverage;
        return output.finish(artifact, recordType, effectiveCoverage, scanned, limitations);
    }

    private static ObjectNode change(
            JsonNode from, JsonNode to, List<String> valueNames) {
        ObjectNode result = JSON.createObjectNode();
        result.put("tracepointId", to.path("tracepointId").asText());
        result.put("fromSequence", sequence(from, false));
        result.put("toSequence", sequence(to, false));
        long skipped = Math.max(0,
                to.path("matchedHit").asLong() - from.path("matchedHit").asLong() - 1);
        result.put("skippedMatchedHits", skipped);
        ArrayNode changes = result.putArray("changes");
        for (String name : valueNames) {
            JsonNode oldValue = findValue(from.path("projections"), "valuePath", name);
            JsonNode newValue = findValue(to.path("projections"), "valuePath", name);
            String oldStatus = oldValue.path("status").asText("MISSING");
            String newStatus = newValue.path("status").asText("MISSING");
            String oldScalar = scalarText(oldValue.get("scalarValue"));
            String newScalar = scalarText(newValue.get("scalarValue"));
            if (oldStatus.equals(newStatus) && oldScalar.equals(newScalar)) continue;
            ObjectNode item = changes.addObject();
            item.put("valueName", name);
            item.put("fromStatus", oldStatus);
            item.put("fromValue", oldScalar);
            item.put("toStatus", newStatus);
            item.put("toValue", newScalar);
        }
        return result;
    }

    private static boolean matchesRecord(
            JsonNode record, EvidenceQueryRequest request, boolean codePath) {
        EvidenceQueryFilter filter = request.filter();
        if (codePath && !matchesText(record.path("methodRef"), filter.methodRef())) return false;
        if (!codePath && !matchesText(record.path("tracepointId"), filter.tracepointId())) return false;
        if (!matchesSequence(record, filter, codePath)) return false;
        if (!matchesValues(record.path("projections"), codePath ? "name" : "valuePath",
                codePath ? "value" : "scalarValue", "status", filter)) return false;
        for (EvidenceValuePredicate predicate : request.predicates()) {
            if (!matchesPredicate(record.path("projections"), codePath, predicate)) return false;
        }
        return true;
    }

    private static boolean matchesPredicate(
            JsonNode values, boolean codePath, EvidenceValuePredicate predicate) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (matchesText(value.path(codePath ? "name" : "valuePath"),
                    Optional.of(predicate.valueName()))
                    && matchesScalar(value.get(codePath ? "value" : "scalarValue"),
                    predicate.scalarValue())
                    && matchesText(value.path("status"), predicate.valueStatus())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesValues(
            JsonNode values,
            String nameField,
            String scalarField,
            String statusField,
            EvidenceQueryFilter filter) {
        boolean constrained = filter.valueName().isPresent()
                || filter.scalarValue().isPresent() || filter.valueStatus().isPresent();
        if (!constrained) return true;
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (matchesText(value.path(nameField), filter.valueName())
                    && matchesScalar(value.get(scalarField), filter.scalarValue())
                    && matchesText(value.path(statusField), filter.valueStatus())) return true;
        }
        return false;
    }

    private static Optional<String> groupKey(
            JsonNode record, EvidenceQueryRequest request, boolean codePath) {
        return switch (request.groupBy().orElseThrow()) {
            case METHOD_REF -> text(record.path("methodRef"));
            case TRACEPOINT_ID -> text(record.path("tracepointId"));
            case PROJECTION_VALUE -> projectionGroup(record, request, codePath, false);
            case VALUE_STATUS -> projectionGroup(record, request, codePath, true);
        };
    }

    private static Optional<String> projectionGroup(
            JsonNode record, EvidenceQueryRequest request, boolean codePath, boolean status) {
        JsonNode value = findValue(record.path("projections"),
                codePath ? "name" : "valuePath", request.groupValueName().orElseThrow());
        if (value.isMissingNode()) return Optional.empty();
        if (status) return text(value.path("status"));
        JsonNode scalar = value.get(codePath ? "value" : "scalarValue");
        if (scalar == null || scalar.isMissingNode()) {
            return Optional.of("<" + value.path("status").asText("MISSING") + ">");
        }
        return Optional.of(scalarText(scalar));
    }

    private long scanCodePath(Path file, Consumer<JsonNode> consumer) {
        long scanned = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (++scanned > MAX_RECORDS) throw scanLimit();
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
                    throw new WorkspaceException(
                            "CASE_EVIDENCE_QUERY_RECORD_TOO_LARGE", "CodePath record exceeds 1 MiB");
                }
                consumer.accept(JSON.readTree(line));
            }
            return scanned;
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_READ_FAILED", "Failed to query CodePath evidence", failure);
        }
    }

    private EvidenceSourceCoverage collectionCoverage(
            CaseId caseId, ArtifactReference artifact) {
        String[] segments = artifact.relativePath().split("/");
        for (int index = 0; index + 1 < segments.length; index++) {
            if (!"collections".equals(segments[index])) continue;
            try {
                CollectionExecutionSummary summary = repository.requireCollectionExecutionSummary(
                        caseId, new CollectionId(segments[index + 1]));
                return summary.sourceCoverage();
            } catch (RuntimeException ignored) {
                return EvidenceSourceCoverage.UNKNOWN;
            }
        }
        return EvidenceSourceCoverage.UNKNOWN;
    }

    private static EvidenceQueryResult result(
            ArtifactReference artifact,
            String recordType,
            EvidenceQueryRequest request,
            EvidenceSourceCoverage coverage,
            long scanned,
            long matched,
            int returned,
            boolean queryMoreAvailable,
            List<String> modeLimitations,
            String output) {
        EvidenceQueryOutcome outcome = matched > 0
                ? EvidenceQueryOutcome.MATCHED : EvidenceQueryOutcome.NO_MATCH;
        List<String> limitations = new ArrayList<>(modeLimitations);
        if (coverage == EvidenceSourceCoverage.PARTIAL) limitations.add("SOURCE_EVIDENCE_PARTIAL");
        if (coverage == EvidenceSourceCoverage.UNKNOWN) limitations.add("SOURCE_COVERAGE_UNKNOWN");
        if (queryMoreAvailable) limitations.add("QUERY_RESULT_TRUNCATED");
        EvidenceQueryNextAction next = nextAction(request.mode(), outcome, coverage, queryMoreAvailable);
        return new EvidenceQueryResult(
                SchemaVersions.EVIDENCE_QUERY_RESULT, artifact, recordType,
                request.mode(), outcome, coverage, request, scanned, matched, returned,
                queryMoreAvailable, List.copyOf(new LinkedHashSet<>(limitations)), next, output);
    }

    private static EvidenceQueryNextAction nextAction(
            EvidenceQueryMode mode,
            EvidenceQueryOutcome outcome,
            EvidenceSourceCoverage coverage,
            boolean queryMoreAvailable) {
        if (mode == EvidenceQueryMode.SUMMARY || mode == EvidenceQueryMode.COUNT) {
            return EvidenceQueryNextAction.REFINE_QUERY;
        }
        if (queryMoreAvailable) return EvidenceQueryNextAction.NARROW_OR_PAGE;
        if (outcome == EvidenceQueryOutcome.NO_MATCH) {
            return coverage == EvidenceSourceCoverage.COMPLETE
                    ? EvidenceQueryNextAction.TREAT_AS_SCOPED_ABSENCE
                    : EvidenceQueryNextAction.RECOLLECT_WITH_BETTER_SCOPE;
        }
        return coverage == EvidenceSourceCoverage.COMPLETE
                ? EvidenceQueryNextAction.INSPECT_MATCHED_RECORDS
                : EvidenceQueryNextAction.VERIFY_WITH_SOURCE;
    }

    private static String serializeRows(List<JsonNode> rows, int maxBytes) {
        StringBuilder output = new StringBuilder();
        int outputBytes = 0;
        for (JsonNode row : rows) {
            String line = serializeJsonLine(row);
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (outputBytes + lineBytes > maxBytes) {
                throw new WorkspaceException(
                        "CASE_EVIDENCE_QUERY_BUDGET_TOO_SMALL",
                        "maxBytes cannot contain the requested window");
            }
            output.append(line);
            outputBytes += lineBytes;
        }
        return output.toString();
    }

    private static String oneLine(JsonNode value, int maxBytes) {
        String output = serializeJsonLine(value);
        if (output.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_BUDGET_TOO_SMALL",
                    "maxBytes cannot contain the requested result");
        }
        return output;
    }

    private static boolean fits(JsonNode value, int maxBytes) {
        return serializeJsonLine(value).getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    private static String serializeJsonLine(JsonNode value) {
        try {
            return JSON.writeValueAsString(value) + "\n";
        } catch (IOException failure) {
            throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_SERIALIZATION_FAILED",
                    "Failed to serialize Evidence Query result", failure);
        }
    }

    private static ArrayNode topCounts(Map<String, Long> counts, int limit) {
        ArrayNode result = JSON.createArrayNode();
        List<Map.Entry<String, Long>> sorted = sortedCounts(counts);
        for (int index = 0; index < Math.min(limit, sorted.size()); index++) {
            Map.Entry<String, Long> entry = sorted.get(index);
            result.addObject().put("key", entry.getKey()).put("count", entry.getValue());
        }
        return result;
    }

    private static List<Map.Entry<String, Long>> sortedCounts(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
    }

    private static void increment(Map<String, Long> counts, String key) {
        if (!key.isEmpty()) counts.merge(key, 1L, Long::sum);
    }

    private static JsonNode findValue(JsonNode values, String nameField, String name) {
        if (values.isArray()) for (JsonNode value : values) {
            if (name.equals(value.path(nameField).asText())) return value;
        }
        return JSON.missingNode();
    }

    private static Optional<String> text(JsonNode node) {
        return node.isTextual() && !node.textValue().isEmpty()
                ? Optional.of(node.textValue()) : Optional.empty();
    }

    private static boolean matchesText(JsonNode node, Optional<String> expected) {
        return expected.isEmpty()
                || (node.isTextual() && expected.orElseThrow().equals(node.textValue()));
    }

    private static boolean matchesScalar(JsonNode node, Optional<String> expected) {
        if (expected.isEmpty()) return true;
        if (node == null || node.isContainerNode() || node.isMissingNode()) return false;
        return expected.orElseThrow().equals(scalarText(node));
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isMissingNode()) return "";
        return node.isNull() ? "null" : node.asText();
    }

    private static boolean matchesSequence(
            JsonNode record, EvidenceQueryFilter filter, boolean codePath) {
        if (filter.sequenceFrom().isEmpty() && filter.sequenceTo().isEmpty()) return true;
        long sequence = sequence(record, codePath);
        if (sequence < 1) return false;
        return filter.sequenceFrom().map(value -> sequence >= value).orElse(true)
                && filter.sequenceTo().map(value -> sequence <= value).orElse(true);
    }

    private static long sequence(JsonNode record, boolean codePath) {
        JsonNode node = codePath ? record.path("sequence") : record.path("provenance").path("sequence");
        return node.isIntegralNumber() ? node.longValue() : -1;
    }

    private static WorkspaceException scanLimit() {
        return new WorkspaceException(
                "CASE_EVIDENCE_QUERY_SCAN_LIMIT_EXCEEDED",
                "Evidence Query exceeds 100000 records; narrow the collection or query");
    }

    private static void validateIdentity(
            CaseId caseId, String artifactId, EvidenceQueryRequest request) {
        if (caseId == null || artifactId == null || artifactId.isBlank()
                || artifactId.contains("/") || artifactId.contains("\\")
                || artifactId.contains(":") || request == null) {
            throw new IllegalArgumentException("Evidence Query parameters are invalid");
        }
    }

    @FunctionalInterface
    private interface RecordScanner {
        long scan(Consumer<JsonNode> consumer);
    }

    private static final class ResultAccumulator {
        private final EvidenceQueryRequest request;
        private final StringBuilder output = new StringBuilder();
        private long matched;
        private int returned;
        private int outputBytes;
        private boolean outputExhausted;

        private ResultAccumulator(EvidenceQueryRequest request) {
            this.request = request;
        }

        private void match(JsonNode record) {
            long current = matched++;
            if (current < request.offset() || returned >= request.limit() || outputExhausted) return;
            String serialized = serializeJsonLine(record);
            int bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
            if (outputBytes + bytes > request.maxBytes()) {
                if (returned == 0) {
                    throw new WorkspaceException(
                            "CASE_EVIDENCE_QUERY_BUDGET_TOO_SMALL",
                            "maxBytes cannot contain the first requested record");
                }
                outputExhausted = true;
                return;
            }
            output.append(serialized);
            outputBytes += bytes;
            returned++;
        }

        private EvidenceQueryResult finish(
                ArtifactReference artifact,
                String recordType,
                EvidenceSourceCoverage coverage,
                long scanned,
                List<String> limitations) {
            boolean more = outputExhausted
                    || (long) request.offset() + returned < matched;
            return result(artifact, recordType, request, coverage, scanned, matched,
                    returned, more, limitations, output.toString());
        }
    }
}
