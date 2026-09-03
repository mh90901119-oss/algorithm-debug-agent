package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class P4EvidenceJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module());

    @Test
    void roundTripsEvidenceBuildRequest() throws Exception {
        EvidenceBuildRequest request = new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"),
                List.of(new CollectionId("collection-1")), List.of(),
                Set.of(EvidenceDimension.RUNTIME_STATE), 512 * 1024, 1024 * 1024,
                Instant.parse("2026-08-18T00:00:00Z"));

        byte[] json = MAPPER.writeValueAsBytes(request);
        assertEquals("run-1", MAPPER.readTree(json).path("runId").asText());
        assertEquals(request, MAPPER.readValue(json, EvidenceBuildRequest.class));
    }

    @Test
    void rejectsUnknownRequestField() throws Exception {
        JsonNode root = MAPPER.readTree(MAPPER.writeValueAsBytes(new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"),
                List.of(), List.of(), Set.of(EvidenceDimension.TARGET_OUTCOME),
                512 * 1024, 1024 * 1024, Instant.parse("2026-08-18T00:00:00Z"))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("unexpectedField", true);

        assertThrows(UnrecognizedPropertyException.class,
                () -> MAPPER.treeToValue(root, EvidenceBuildRequest.class));
    }

    @Test
    void allP4SchemasAreStrictObjects() throws Exception {
        for (String relative : List.of(
                "trace/normalization-manifest-v2.schema.json",
                "trace/method-path-summary-v4.schema.json",
                "trace/jdwp-snapshot-summary-v3.schema.json",
                "evidence/collection-validation-v2.schema.json",
                "evidence/evidence-build-request-v2.schema.json",
                "evidence/evidence-bundle-v2.schema.json",
                "evidence/sufficiency-evaluation-v2.schema.json")) {
            JsonNode schema = schema(relative);
            assertEquals("object", schema.path("type").asText(), relative);
            assertFalse(schema.path("additionalProperties").asBoolean(true), relative);
        }
    }

    private static JsonNode schema(String relative) throws Exception {
        String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
        return MAPPER.readTree(Path.of(root, "schemas", relative).toFile());
    }
}
