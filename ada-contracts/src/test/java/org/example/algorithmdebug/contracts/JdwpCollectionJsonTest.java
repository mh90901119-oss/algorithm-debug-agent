package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JdwpCollectionJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String HASH = "a".repeat(64);

    @Test
    void roundTripsPlanAndKeepsSchemaFieldsAligned() throws Exception {
        JdwpCollectionPlan plan = plan();

        byte[] json = MAPPER.writeValueAsBytes(plan);
        assertEquals(plan, MAPPER.readValue(json, JdwpCollectionPlan.class));

        JsonNode schema = schema("jdwp-plan-v2.schema.json");
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertEquals(Set.of(
                "schemaVersion", "planId", "caseId", "contextId", "analysisId",
                "targetTest", "tracepoints", "budget",
                "rationale", "createdAt"), required);
        for (String id : List.of("planId", "caseId", "contextId", "analysisId")) {
            assertEquals("string", schema.path("properties").path(id).path("type").asText(), id);
        }
        assertEquals("#/$defs/tracepoint",
                schema.path("properties").path("tracepoints").path("items").path("$ref").asText());
        assertEquals("#/$defs/capture",
                schema.path("$defs").path("tracepoint").path("properties")
                        .path("capture").path("$ref").asText());
        JsonSchemaTestSupport.assertValid(schemaPath("jdwp-plan-v2.schema.json"),
                MAPPER.writeValueAsString(plan));
    }

    @Test
    void rejectsUnsupportedCollectorCapabilitiesInsteadOfIgnoringThem() throws Exception {
        JsonNode root = MAPPER.valueToTree(plan());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("tracepoints").path(0)
                .path("capture")).putArray("localVariables").add("decision");

        assertThrows(UnrecognizedPropertyException.class, () ->
                MAPPER.treeToValue(root, JdwpCollectionPlan.class));
        assertFalse(schema("jdwp-plan-v2.schema.json")
                .path("$defs").path("capture").path("additionalProperties").asBoolean(true));
    }

    @Test
    void schemasExposeP3HardLimitsAndRejectRemoteHostFields() throws Exception {
        JsonNode plan = schema("jdwp-plan-v2.schema.json");
        JsonNode tracepoints = plan.path("properties").path("tracepoints");
        JsonNode budget = plan.path("$defs").path("budget").path("properties");

        assertEquals(20, tracepoints.path("maxItems").asInt());
        assertEquals(1_000, budget.path("maxEvents").path("maximum").asInt());
        assertEquals(50L * 1024 * 1024,
                budget.path("maxBytes").path("maximum").asLong());
        assertFalse(plan.path("properties").has("host"));
        assertFalse(plan.toString().contains("projection"));
        assertFalse(plan.toString().contains("sampling"));
        assertTrue(schema("jdwp-manifest-v2.schema.json")
                .path("properties").has("rawTraceRelativePath"));
        assertFalse(schema("jdwp-manifest-v2.schema.json")
                .path("properties").has("toolSha256"));
    }

    @Test
    void roundTripsManifestWithoutCollectorJarFingerprint() throws Exception {
        JdwpCollectionManifest manifest = new JdwpCollectionManifest(
                SchemaVersions.JDWP_COLLECTION_MANIFEST,
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "jdwp-batch-collector", "1.0.0",
                JdwpCollectionCompletion.SUCCESS, "vm_death", JdwpCollectionStage.BASELINE_CHECKED,
                true, true, 0, 0, false, false, 1, 128,
                Map.of("point-1", 1), Map.of("point-1", 1),
                Optional.empty(), "raw/jdwp.jsonl", "raw/collector-manifest.json",
                "logs/target-stdout.log", "logs/target-stderr.log",
                "logs/collector-stdout.log", "logs/collector-stderr.log",
                Instant.parse("2026-08-18T00:00:00Z"),
                Instant.parse("2026-08-18T00:00:01Z"));

        String json = MAPPER.writeValueAsString(manifest);

        assertEquals(manifest, MAPPER.readValue(json, JdwpCollectionManifest.class));
        assertFalse(MAPPER.readTree(json).has("toolSha256"));
        JsonSchemaTestSupport.assertValid(schemaPath("jdwp-manifest-v2.schema.json"), json);
    }

    private static JdwpCollectionPlan plan() {
        SourceAnchor anchor = new SourceAnchor(
                "fixture.Algorithm", "schedule", "()V",
                "src/main/java/fixture/Algorithm.java", 10, 20);
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN,
                new PlanId("plan-1"), new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new TargetTest("fixture.AlgorithmTest", "runs"),
                List.of(new JdwpTracepointSpec(
                        "point-1", "fixture.Algorithm#schedule()V", anchor, 11, 3,
                        JdwpCaptureSpec.stackOnly())),
                JdwpCollectionBudget.defaults(), "采集关键决策位置",
                Instant.parse("2026-08-18T00:00:00Z"));
    }

    private static JsonNode schema(String fileName) throws Exception {
        return MAPPER.readTree(schemaPath(fileName).toFile());
    }

    private static Path schemaPath(String fileName) {
        String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
        return Path.of(root, "schemas", "collection", fileName);
    }
}
