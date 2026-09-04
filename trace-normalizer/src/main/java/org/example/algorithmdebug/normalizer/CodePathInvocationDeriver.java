package org.example.algorithmdebug.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 将 CodePath Enter/Exit 流配对为可流式查询的调用记录。 */
final class CodePathInvocationDeriver {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    Result derive(CodePathNormalizationInput input) {
        Path output = input.invocationOutputPath();
        Path parent = output.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
            Result result;
            try (BufferedReader source = Files.newBufferedReader(input.rawTracePath(), StandardCharsets.UTF_8);
                 BufferedWriter target = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                result = derive(input, source, target);
            }
            if (result.emittedInvocations() == 0) {
                Files.deleteIfExists(temporary);
                return result;
            }
            moveNew(temporary, output);
            return result;
        } catch (IOException failure) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 原始异常保留为根因，临时文件由后续 Workspace 清理处理。
                }
            }
            throw new NormalizationException(
                    "CODEPATH_INVOCATION_DERIVATION_FAILED",
                    "CodePath invocation derivation failed", 0, failure);
        }
    }

    private Result derive(
            CodePathNormalizationInput input,
            BufferedReader source,
            BufferedWriter target) throws IOException {
        Set<String> selected = input.plan().methodSelections().stream()
                .map(selection -> selection.selector().methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Deque<OpenInvocation> stack = new ArrayDeque<>();
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        long records = 0;
        long observed = 0;
        long emitted = 0;
        long bytes = 0;
        boolean outputExhausted = false;
        String line;
        while ((line = source.readLine()) != null) {
            records++;
            if (records > input.budget().maxRecords()) {
                throw invalid(records, "Raw Trace record count exceeds the normalization budget", null);
            }
            long lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (lineBytes > input.budget().maxRecordBytes()) {
                throw invalid(records, "Raw Trace record exceeds the normalization budget", null);
            }
            JsonNode event;
            try {
                event = MAPPER.readTree(line);
            } catch (IOException failure) {
                throw invalid(records, "Raw Trace contains invalid JSON", failure);
            }
            String methodRef = methodRef(event, records);
            if (!selected.contains(methodRef)) continue;
            String type = requiredText(event, "eventType", records);
            long eventId = requiredLong(event, "eventId", records);
            int depth = Math.toIntExact(requiredLong(event, "depth", records));
            ArrayNode projections = projections(event, records);
            markRequiredProjectionGaps(projections, reasons);
            if ("METHOD_ENTER".equals(type)) {
                stack.push(new OpenInvocation(eventId, depth, methodRef, projections.deepCopy()));
            } else if ("METHOD_EXIT".equals(type)) {
                if (stack.isEmpty() || !stack.peek().methodRef().equals(methodRef)) {
                    reasons.add("TRACE_STRUCTURE_INCOMPLETE");
                    continue;
                }
                OpenInvocation open = stack.pop();
                observed++;
                if (!outputExhausted) {
                    ObjectNode invocation = invocation(observed, open, eventId, projections);
                    String serialized = MAPPER.writeValueAsString(invocation) + "\n";
                    long serializedBytes = serialized.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes + serializedBytes > input.budget().maxRawBytes()) {
                        outputExhausted = true;
                        reasons.add("INVOCATION_OUTPUT_BUDGET_EXCEEDED");
                    } else {
                        target.write(serialized);
                        bytes += serializedBytes;
                        emitted++;
                    }
                }
            } else {
                throw invalid(records, "Unsupported CodePath eventType", null);
            }
        }
        if (!stack.isEmpty()) reasons.add("TRACE_STRUCTURE_INCOMPLETE");
        return new Result(records, observed, emitted, bytes, List.copyOf(reasons));
    }

    private ObjectNode invocation(
            long sequence,
            OpenInvocation open,
            long exitEventId,
            ArrayNode exitProjections) {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("schemaVersion", "1.0");
        value.put("sequence", sequence);
        value.put("methodRef", open.methodRef());
        value.put("enterEventId", open.enterEventId());
        value.put("exitEventId", exitEventId);
        value.put("depth", open.depth());
        ArrayNode merged = value.putArray("projections");
        open.projections().forEach(merged::add);
        exitProjections.forEach(merged::add);
        return value;
    }

    private ArrayNode projections(JsonNode event, long line) {
        JsonNode values = event.get("projections");
        if (!(values instanceof ArrayNode array) || array.size() > 32) {
            throw invalid(line, "projections must be an array with at most 32 entries", null);
        }
        for (JsonNode value : array) {
            requiredText(value, "name", line);
            requiredText(value, "path", line);
            String status = requiredText(value, "status", line);
            if (!List.of("VALUE", "NULL", "UNAVAILABLE", "TRUNCATED").contains(status)
                    || !value.path("required").isBoolean()) {
                throw invalid(line, "projection status or required flag is invalid", null);
            }
            JsonNode scalar = value.get("value");
            if (scalar != null && !scalar.isNull() && !scalar.isValueNode()) {
                throw invalid(line, "projection value must be scalar", null);
            }
        }
        return array;
    }

    private void markRequiredProjectionGaps(ArrayNode projections, Set<String> reasons) {
        for (JsonNode projection : projections) {
            if (projection.path("required").asBoolean()
                    && ("UNAVAILABLE".equals(projection.path("status").asText())
                    || "TRUNCATED".equals(projection.path("status").asText()))) {
                reasons.add("REQUIRED_PROJECTION_UNAVAILABLE");
            }
        }
    }

    private String methodRef(JsonNode event, long line) {
        return requiredText(event, "className", line) + "#"
                + requiredText(event, "methodName", line)
                + requiredText(event, "descriptor", line);
    }

    private String requiredText(JsonNode value, String field, long line) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw invalid(line, field + " is invalid", null);
        }
        return node.textValue();
    }

    private long requiredLong(JsonNode value, String field, long line) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || node.longValue() < 0) {
            throw invalid(line, field + " is invalid", null);
        }
        return node.longValue();
    }

    private NormalizationException invalid(long line, String detail, Throwable cause) {
        return new NormalizationException("NORMALIZE_SCHEMA_UNSUPPORTED", detail, line, cause);
    }

    private void moveNew(Path temporary, Path output) throws IOException {
        if (Files.exists(output)) {
            throw new IOException("CodePath invocation output already exists");
        }
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, output);
        }
    }

    record Result(
            long inputRecords,
            long observedInvocations,
            long emittedInvocations,
            long outputBytes,
            List<String> truncationReasons) {
    }

    private record OpenInvocation(
            long enterEventId,
            int depth,
            String methodRef,
            ArrayNode projections) {
    }
}
