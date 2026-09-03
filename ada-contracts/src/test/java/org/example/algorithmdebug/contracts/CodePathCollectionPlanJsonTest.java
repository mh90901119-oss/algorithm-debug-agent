package org.example.algorithmdebug.contracts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
class CodePathCollectionPlanJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void roundTripsExactV4PlanAndAlignsSchema() throws Exception {
        CodePathCollectionPlan plan = plan(); JsonNode json = MAPPER.valueToTree(plan);
        assertEquals(plan, MAPPER.treeToValue(json, CodePathCollectionPlan.class));
        assertEquals("Which path ran?", json.path("intent").path("questionToAnswer").asText());
        for (String old : List.of("sourceFingerprintSha256", "packagePrefixes", "captureScope", "estimatedPackageEvents")) assertFalse(json.has(old));
        assertFalse(json.path("budget").has("maxCallDepth")); JsonNode schema = schema(); Set<String> required = new HashSet<>(); schema.path("required").forEach(v -> required.add(v.asText()));
        assertEquals(Set.of("schemaVersion", "planId", "caseId", "analysisId", "targetTest", "methodSelections", "budget", "rationale", "intent", "createdAt"), required);
        for (String id : List.of("planId", "caseId", "analysisId")) {
            assertEquals("string", schema.path("properties").path(id).path("type").asText(), id);
        }
        assertEquals("#/$defs/targetTest", schema.path("properties").path("targetTest").path("$ref").asText());
        JsonNode target = schema.path("$defs").path("targetTest");
        assertFalse(target.path("additionalProperties").asBoolean(true));
        assertEquals(Set.of("className", "methodName"), fields(target.path("required")));
        JsonSchemaTestSupport.assertValid(schemaPath(), MAPPER.writeValueAsString(plan));
    }
    @Test void enforcesBudgetsAndExactSelector() {
        assertThrows(IllegalArgumentException.class, () -> new CollectionBudget(1, 50L * 1024 * 1024 + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CollectionBudget(1, 1, 20 * 60_000L + 1));
        assertThrows(IllegalArgumentException.class, () -> new MethodSelector("x#run()", "x.Y", "run", "()"));
    }
    private static CodePathCollectionPlan plan() { return new CodePathCollectionPlan(SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"), new CaseId("case-1"), new AnalysisId("analysis-1"), new TargetTest("fixture.TargetTest", "runs"), List.of(new CodePathMethodSelection(new MethodSelector("fixture.TargetTest#runs()V", "fixture.TargetTest", "runs", "()V"), List.of())), java.util.Optional.empty(), CollectionBudget.defaults(), "Locate the key invocation", new InvestigationIntent("Which path ran?", "One strategy implementation was selected", List.of(), List.of("Observed method path")), Instant.EPOCH); }
    private static JsonNode schema() throws Exception { return MAPPER.readTree(schemaPath().toFile()); }
    private static Path schemaPath() { return Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."), "schemas", "collection", "codepath-plan-v4.schema.json"); }
    private static Set<String> fields(JsonNode values) { Set<String> result = new HashSet<>(); values.forEach(value -> result.add(value.asText())); return result; }
}
