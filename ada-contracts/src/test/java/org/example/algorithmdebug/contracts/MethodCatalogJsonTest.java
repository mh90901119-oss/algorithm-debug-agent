package org.example.algorithmdebug.contracts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
class MethodCatalogJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void roundTripsV2WithoutModuleFingerprintOrPackageCensus() throws Exception {
        SourceAnchor anchor = new SourceAnchor("fixture.TargetTest", "runs", "()V", "src/test/java/fixture/TargetTest.java", 1, 2);
        MethodCatalog catalog = new MethodCatalog(SchemaVersions.METHOD_CATALOG, new CaseId("case-1"), new AnalysisId("analysis-1"), new TargetTest("fixture.TargetTest", "runs"), List.of(new MethodCatalogEntry("fixture.TargetTest#runs()V", anchor, 0, true)), List.of(new MethodCallEdge("fixture.TargetTest#runs()V", "fixture.TargetTest#runs()V", 1, CallResolutionKind.DIRECT)), List.of(), SnapshotCompleteness.COMPLETE, 1, 1, Instant.EPOCH);
        JsonNode json = MAPPER.valueToTree(catalog); assertEquals(catalog, MAPPER.treeToValue(json, MethodCatalog.class));
        assertFalse(json.has("sourceFingerprintSha256")); assertFalse(json.has("packageCensus"));
        JsonNode schema = schema(); Set<String> required = new HashSet<>(); schema.path("required").forEach(v -> required.add(v.asText()));
        assertEquals(Set.of("schemaVersion", "caseId", "analysisId", "targetTest", "entries", "edges", "warnings", "completeness", "discoveredMethodCount", "discoveredEdgeCount", "createdAt"), required);
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        for (String id : List.of("caseId", "analysisId")) {
            assertEquals("string", schema.path("properties").path(id).path("type").asText(), id);
        }
        assertEquals("#/$defs/entry", schema.path("properties").path("entries").path("items").path("$ref").asText());
        assertEquals("#/$defs/edge", schema.path("properties").path("edges").path("items").path("$ref").asText());
        assertEquals("DIRECT", json.path("edges").get(0).path("resolutionKind").textValue());
        assertFalse(required.contains("resolutionKind"));
        assertFalse(schema.path("$defs").path("entry").path("additionalProperties").asBoolean(true));
        JsonSchemaTestSupport.assertValid(schemaPath(), MAPPER.writeValueAsString(catalog));
    }
    private static JsonNode schema() throws Exception { return MAPPER.readTree(schemaPath().toFile()); }
    private static Path schemaPath() { return Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."), "schemas", "analysis", "method-catalog-v3.schema.json"); }
}
