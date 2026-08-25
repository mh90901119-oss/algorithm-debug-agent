package org.example.algorithmdebug.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.TraceProvenance;
import org.example.algorithmdebug.contracts.ValidationFinding;

/** 验证派生事实引用的 Raw JSONL 行号与 eventId/sequence。 */
public final class ProvenanceVerifier {

    private static final int MAX_REFERENCED_LINE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FINDINGS = 128;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param provenances 摘要中全部 Raw 引用
     * @param rawArtifact 该 Collection 的 Raw Artifact
     * @param rawPath Raw JSONL 实际路径
     * @return 空列表表示所有引用均能精确回到 Raw
     */
    public List<ValidationFinding> verify(
            List<TraceProvenance> provenances,
            ArtifactReference rawArtifact,
            Path rawPath) {
        if (provenances == null || rawArtifact == null || rawPath == null
                || provenances.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Provenance 校验参数非法");
        }
        Set<Long> requestedLines = new HashSet<>();
        provenances.stream().filter(value -> value.rawArtifact().equals(rawArtifact))
                .map(TraceProvenance::jsonlLine).forEach(requestedLines::add);
        Map<Long, Observation> observations;
        try {
            observations = readObservations(rawPath, requestedLines);
        } catch (IOException | RuntimeException failure) {
            return List.of(finding(
                    "PROVENANCE_RAW_READ_FAILED", "无法流式读取 Raw JSONL",
                    rawArtifact, Optional.empty()));
        }
        ArrayList<ValidationFinding> findings = new ArrayList<>();
        for (TraceProvenance provenance : provenances) {
            if (findings.size() >= MAX_FINDINGS) break;
            if (!provenance.rawArtifact().equals(rawArtifact)) {
                findings.add(finding(
                        "PROVENANCE_ARTIFACT_MISMATCH", "事实引用了其他 Raw Artifact",
                        rawArtifact, Optional.of(provenance)));
                continue;
            }
            Observation observed = observations.get(provenance.jsonlLine());
            if (observed == null) {
                findings.add(finding(
                        "PROVENANCE_LINE_OUT_OF_RANGE", "事实引用的 JSONL 行不存在",
                        rawArtifact, Optional.of(provenance)));
                continue;
            }
            if (observed.invalidJson()) {
                findings.add(finding(
                        "PROVENANCE_RAW_JSON_INVALID", "事实引用的 JSONL 行不是有效对象",
                        rawArtifact, Optional.of(provenance)));
                continue;
            }
            if (provenance.eventId().isPresent()
                    && !matches(observed.eventId(), provenance.eventId().orElseThrow())) {
                findings.add(finding(
                        "PROVENANCE_EVENT_ID_MISMATCH", "Raw eventId 与事实引用不一致",
                        rawArtifact, Optional.of(provenance)));
                continue;
            }
            if (provenance.sequence().isPresent()
                    && !matches(observed.sequence(), provenance.sequence().orElseThrow())) {
                findings.add(finding(
                        "PROVENANCE_SEQUENCE_MISMATCH", "Raw sequence 与事实引用不一致",
                        rawArtifact, Optional.of(provenance)));
            }
        }
        return List.copyOf(findings);
    }

    private Map<Long, Observation> readObservations(Path path, Set<Long> requested)
            throws IOException {
        HashMap<Long, Observation> result = new HashMap<>();
        byte[] buffer = new byte[8 * 1024];
        long line = 1;
        boolean retain = requested.contains(line);
        ByteArrayOutputStream current = retain ? new ByteArrayOutputStream() : null;
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                for (int index = 0; index < count; index++) {
                    byte value = buffer[index];
                    if (value == '\n') {
                        if (retain) result.put(line, parse(current.toByteArray()));
                        line++;
                        retain = requested.contains(line);
                        current = retain ? new ByteArrayOutputStream() : null;
                    } else if (retain) {
                        if (current.size() >= MAX_REFERENCED_LINE_BYTES) {
                            result.put(line, Observation.invalid());
                            retain = false;
                            current = null;
                        } else {
                            current.write(value);
                        }
                    }
                }
            }
        }
        if (retain && current != null && current.size() > 0) {
            result.put(line, parse(current.toByteArray()));
        }
        return result;
    }

    private Observation parse(byte[] bytes) {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        try {
            JsonNode json = mapper.readTree(bytes, 0, length);
            if (json == null || !json.isObject()) return Observation.invalid();
            return new Observation(integral(json.get("eventId")),
                    integral(json.get("sequence")), false);
        } catch (IOException failure) {
            return Observation.invalid();
        }
    }

    private static OptionalLong integral(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                ? OptionalLong.of(value.longValue()) : OptionalLong.empty();
    }

    private static boolean matches(OptionalLong observed, long expected) {
        return observed.isPresent() && observed.getAsLong() == expected;
    }

    private static ValidationFinding finding(
            String code,
            String detail,
            ArtifactReference artifact,
            Optional<TraceProvenance> provenance) {
        return new ValidationFinding(
                code, EvidenceValidationStatus.INVALID, detail,
                List.of(artifact), provenance);
    }

    private record Observation(
            OptionalLong eventId,
            OptionalLong sequence,
            boolean invalidJson) {
        private static Observation invalid() {
            return new Observation(OptionalLong.empty(), OptionalLong.empty(), true);
        }
    }
}
