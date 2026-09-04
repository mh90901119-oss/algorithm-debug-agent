package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceQueryFilter;
import org.example.algorithmdebug.contracts.EvidenceQueryResult;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 查询已注册且完整性校验通过的 CodePath/JDWP 派生证据。
 *
 * <p>实现只执行字段匹配、分页和字节限制，不推断任何业务含义，也不创建新 Artifact。</p>
 */
public final class RegisteredEvidenceQuery {
    public static final int MAX_LIMIT = 50;
    public static final int MAX_OUTPUT_BYTES = 65_536;
    private static final int MAX_RECORD_BYTES = 1_048_576;
    private static final long MAX_RECORDS = 100_000;
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

    /** 对受支持的已注册动态证据执行有界查询。 */
    public EvidenceQueryResult query(
            CaseId caseId,
            String artifactId,
            EvidenceQueryFilter filter,
            int offset,
            int limit,
            int maxBytes) {
        if (caseId == null || artifactId == null || artifactId.isBlank()
                || artifactId.contains("/") || artifactId.contains("\\") || artifactId.contains(":")
                || filter == null || offset < 0 || limit < 1 || limit > MAX_LIMIT
                || maxBytes < 1 || maxBytes > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("Evidence Query parameters are invalid");
        }
        ArtifactReference artifact = repository.requireArtifactRegistration(
                caseId, artifactId).artifact();
        Path file = access.requireVerifiedArtifact(caseId, artifact);
        return switch (artifact.artifactType()) {
            case "CODEPATH_INVOCATIONS" -> queryCodePath(
                    artifact, file, filter, offset, limit, maxBytes);
            case "JDWP_SNAPSHOT_SUMMARY" -> queryJdwp(
                    artifact, file, filter, offset, limit, maxBytes);
            default -> throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_ARTIFACT_UNSUPPORTED",
                    "Evidence Query supports only CodePath invocations and JDWP snapshot summaries");
        };
    }

    private EvidenceQueryResult queryCodePath(
            ArtifactReference artifact,
            Path file,
            EvidenceQueryFilter filter,
            int offset,
            int limit,
            int maxBytes) {
        if (filter.tracepointId().isPresent()) {
            throw new IllegalArgumentException("tracepointId is not valid for CodePath invocations");
        }
        ResultAccumulator result = new ResultAccumulator(offset, limit, maxBytes);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.scan();
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_RECORD_BYTES) {
                    throw new WorkspaceException(
                            "CASE_EVIDENCE_QUERY_RECORD_TOO_LARGE", "CodePath record exceeds 1 MiB");
                }
                JsonNode record = JSON.readTree(line);
                if (matchesCodePath(record, filter)) result.match(record);
            }
        } catch (WorkspaceException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_READ_FAILED", "Failed to query CodePath evidence", failure);
        }
        return result.finish(artifact, "CODEPATH_INVOCATION", filter);
    }

    private EvidenceQueryResult queryJdwp(
            ArtifactReference artifact,
            Path file,
            EvidenceQueryFilter filter,
            int offset,
            int limit,
            int maxBytes) {
        if (filter.methodRef().isPresent()) {
            throw new IllegalArgumentException("methodRef is not valid for JDWP snapshots");
        }
        JsonNode document = mapper.readJsonArtifact(file, JsonNode.class);
        JsonNode hits = document.path("hits");
        if (!hits.isArray()) {
            throw new WorkspaceException(
                    "CASE_EVIDENCE_QUERY_SCHEMA_INVALID", "JDWP summary does not contain a hits array");
        }
        ResultAccumulator result = new ResultAccumulator(offset, limit, maxBytes);
        for (JsonNode hit : hits) {
            result.scan();
            if (matchesJdwp(hit, filter)) result.match(hit);
        }
        return result.finish(artifact, "JDWP_SNAPSHOT", filter);
    }

    private static boolean matchesCodePath(JsonNode record, EvidenceQueryFilter filter) {
        if (!matchesText(record.path("methodRef"), filter.methodRef())) return false;
        if (!matchesSequence(record.path("sequence"), filter)) return false;
        return matchesValues(record.path("projections"), "name", "value", "status", filter);
    }

    private static boolean matchesJdwp(JsonNode record, EvidenceQueryFilter filter) {
        if (!matchesText(record.path("tracepointId"), filter.tracepointId())) return false;
        if (!matchesSequence(record.path("provenance").path("sequence"), filter)) return false;
        return matchesValues(
                record.path("projections"), "valuePath", "scalarValue", "status", filter);
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
                    && matchesText(value.path(statusField), filter.valueStatus())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesText(JsonNode node, Optional<String> expected) {
        return expected.isEmpty() || (node.isTextual() && expected.orElseThrow().equals(node.textValue()));
    }

    private static boolean matchesScalar(JsonNode node, Optional<String> expected) {
        if (expected.isEmpty()) return true;
        if (node == null || node.isContainerNode() || node.isMissingNode()) return false;
        String actual = node.isNull() ? "null" : node.asText();
        return expected.orElseThrow().equals(actual);
    }

    private static boolean matchesSequence(JsonNode node, EvidenceQueryFilter filter) {
        if (filter.sequenceFrom().isEmpty() && filter.sequenceTo().isEmpty()) return true;
        if (!node.isIntegralNumber()) return false;
        long sequence = node.longValue();
        return filter.sequenceFrom().map(value -> sequence >= value).orElse(true)
                && filter.sequenceTo().map(value -> sequence <= value).orElse(true);
    }

    private static final class ResultAccumulator {
        private final int offset;
        private final int limit;
        private final int maxBytes;
        private final StringBuilder output = new StringBuilder();
        private long scanned;
        private long matched;
        private int returned;
        private int outputBytes;
        private boolean outputExhausted;

        private ResultAccumulator(int offset, int limit, int maxBytes) {
            this.offset = offset;
            this.limit = limit;
            this.maxBytes = maxBytes;
        }

        private void scan() {
            scanned++;
            if (scanned > MAX_RECORDS) {
                throw new WorkspaceException(
                        "CASE_EVIDENCE_QUERY_SCAN_LIMIT_EXCEEDED", "Evidence Query exceeds 100000 records");
            }
        }

        private void match(JsonNode record) {
            long current = matched++;
            if (current < offset || returned >= limit || outputExhausted) return;
            try {
                String serialized = JSON.writeValueAsString(record) + "\n";
                int bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
                if (outputBytes + bytes > maxBytes) {
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
            } catch (IOException failure) {
                throw new WorkspaceException(
                        "CASE_EVIDENCE_QUERY_SERIALIZATION_FAILED",
                        "Failed to serialize Evidence Query record", failure);
            }
        }

        private EvidenceQueryResult finish(
                ArtifactReference artifact, String recordType, EvidenceQueryFilter filter) {
            return new EvidenceQueryResult(
                    SchemaVersions.EVIDENCE_QUERY_RESULT, artifact, recordType, filter,
                    scanned, matched, offset, limit, returned,
                    (long) offset + returned < matched, output.toString());
        }
    }
}
