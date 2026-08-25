package org.example.algorithmdebug.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.algorithmdebug.contracts.CaseArtifactRegistration;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.GanttInspection;
import org.example.algorithmdebug.contracts.ProjectId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 通过已注册 Artifact ID 有界读取 Gantt JSON，不解释调度业务语义。 */
public final class GanttArtifactInspector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BoundedDocumentMapper mapper = new BoundedDocumentMapper();
    private final ArtifactIntegrityChecker integrity = new ArtifactIntegrityChecker();

    public GanttInspection inspect(Path workspace, ProjectId projectId, CaseId caseId, String artifactId,
            String operation, String jsonPointer, int offset, int limit) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9._-]{1,256}"))
            throw new WorkspaceException("GANTT_ARTIFACT_ID_INVALID", "Gantt Artifact ID is invalid");
        if (!("summary".equals(operation) || "slice".equals(operation)) || offset < 0 || limit < 1 || limit > 100)
            throw new WorkspaceException("GANTT_INSPECTION_REQUEST_INVALID", "Gantt inspection request is invalid");
        String pointer = jsonPointer == null ? "" : jsonPointer;
        Path caseRoot = WorkspaceLayout.of(workspace).projectCases(projectId).resolve(caseId.value()).normalize();
        Path registrationPath = caseRoot.resolve("artifacts").resolve(artifactId + ".json").normalize();
        if (!registrationPath.startsWith(caseRoot) || !Files.isRegularFile(registrationPath, LinkOption.NOFOLLOW_LINKS))
            throw new WorkspaceException("CASE_ARTIFACT_NOT_REGISTERED", "Gantt Artifact is not registered");
        CaseArtifactRegistration registration = mapper.readJson(registrationPath, CaseArtifactRegistration.class);
        if (!registration.caseId().equals(caseId) || !registration.artifact().artifactType().contains("GANTT"))
            throw new WorkspaceException("GANTT_ARTIFACT_TYPE_INVALID", "Artifact is not a Gantt JSON");
        Path artifact = caseRoot.resolve(registration.artifact().relativePath()).normalize();
        if (!artifact.startsWith(caseRoot)
                || integrity.verify(registration.artifact(), artifact).status() != ArtifactIntegrityChecker.Status.VALID)
            throw new WorkspaceException("CASE_ARTIFACT_INTEGRITY_MISMATCH", "Gantt Artifact integrity check failed");
        JsonNode selected = mapper.readJsonArtifact(artifact, JsonNode.class);
        selected = pointer.isEmpty() ? selected : selected.at(pointer);
        if (selected.isMissingNode())
            throw new WorkspaceException("GANTT_JSON_POINTER_NOT_FOUND", "Gantt JSON pointer was not found");
        ArrayList<String> fields = new ArrayList<>();
        if (selected.isObject()) selected.fieldNames().forEachRemaining(name -> { if (fields.size() < 256) fields.add(name); });
        long total = selected.isContainerNode() ? selected.size() : 1;
        JsonNode output = "summary".equals(operation) ? JSON.createObjectNode() : slice(selected, offset, limit);
        int returned = "summary".equals(operation) ? 0 : selected.isContainerNode() ? output.size() : 1;
        String json = "summary".equals(operation) ? "" : new String(mapper.writeJson(output), StandardCharsets.UTF_8);
        return new GanttInspection("1.0", caseId, artifactId, operation, pointer, selected.getNodeType().name(),
                total, offset, returned, offset + returned < total, List.copyOf(fields), json);
    }

    private static JsonNode slice(JsonNode selected, int offset, int limit) {
        if (selected.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            for (int index = offset; index < selected.size() && result.size() < limit; index++) result.add(selected.get(index));
            return result;
        }
        if (selected.isObject()) {
            ObjectNode result = JSON.createObjectNode(); ArrayList<String> names = new ArrayList<>();
            selected.fieldNames().forEachRemaining(names::add);
            for (int index = offset; index < names.size() && result.size() < limit; index++) {
                String name = names.get(index); result.set(name, selected.get(name));
            }
            return result;
        }
        return selected;
    }
}
