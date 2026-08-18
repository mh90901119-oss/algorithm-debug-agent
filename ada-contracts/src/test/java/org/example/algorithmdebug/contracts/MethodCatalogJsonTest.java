package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MethodCatalogJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void roundTripsMethodCatalogAndKeepsSchemaAligned() throws Exception {
        MethodCatalogEntry target = new MethodCatalogEntry(
                "fixture.TargetTest#caseUnderTest()V",
                new SourceAnchor(
                        "fixture.TargetTest", "caseUnderTest", "()V",
                        "src/test/java/fixture/TargetTest.java", 10, 14, "b".repeat(64)),
                0,
                true);
        MethodCatalog catalog = new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"),
                new ContextId("context-1"),
                new AnalysisId("analysis-1"),
                new TargetTest("fixture.TargetTest", "caseUnderTest"),
                "a".repeat(64),
                List.of(target),
                List.of(),
                List.of(),
                List.of(new PackageCensusEntry("fixture", 1)),
                SnapshotCompleteness.COMPLETE,
                SnapshotCompleteness.COMPLETE,
                1,
                0,
                Instant.parse("2026-08-18T00:00:00Z"));

        byte[] json = MAPPER.writeValueAsBytes(catalog);
        assertEquals(catalog, MAPPER.readValue(json, MethodCatalog.class));

        String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
        JsonNode schema = MAPPER.readTree(Path.of(
                root, "schemas", "analysis", "method-catalog-v1.schema.json").toFile());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(SchemaVersions.METHOD_CATALOG,
                schema.path("properties").path("schemaVersion").path("const").asText());
        Set<String> actual = new HashSet<>();
        schema.path("required").forEach(node -> actual.add(node.asText()));
        assertEquals(Set.of(
                "schemaVersion", "caseId", "contextId", "analysisId", "targetTest",
                "sourceFingerprintSha256", "entries", "edges", "warnings", "completeness",
                "packageCensus", "packageCensusCompleteness", "discoveredMethodCount",
                "discoveredEdgeCount", "createdAt"), actual);
    }

    @Test
    void methodCatalogSchemaValidatesJvmDescriptorInstances() throws Exception {
        JsonNode anchor = schema().path("$defs").path("anchor");
        String expression = anchor.path("properties")
                .path("descriptor").path("pattern").asText();
        Pattern descriptor = Pattern.compile(expression);

        assertFalse(expression.isBlank());
        assertEquals(true, descriptor.matcher("(Ljava/lang/String;[I)Ljava/util/List;").matches());
        assertFalse(descriptor.matcher("()").matches());
        assertFalse(descriptor.matcher("(V)V").matches());
        assertFalse(descriptor.matcher("(Ljava/lang/String)V").matches());
        assertEquals(true, schemaAllowsDescriptor(anchor, "<init>", "()V"));
        assertFalse(schemaAllowsDescriptor(anchor, "<init>", "()I"));
        assertEquals(true, schemaAllowsDescriptor(anchor, "method", "()I"));
    }

    private static boolean schemaAllowsDescriptor(
            JsonNode objectSchema, String methodName, String descriptor) {
        String generalExpression = objectSchema.path("properties")
                .path("descriptor").path("pattern").asText();
        if (generalExpression.isBlank()
                || !Pattern.compile(generalExpression).matcher(descriptor).matches()) {
            return false;
        }
        JsonNode constructorRule = objectSchema.path("allOf").path(0);
        String constructorName = constructorRule.path("if").path("properties")
                .path("methodName").path("const").asText();
        if (!methodName.equals(constructorName)) {
            return true;
        }
        String constructorDescriptorExpression = constructorRule.path("then").path("properties")
                .path("descriptor").path("pattern").asText();
        return !constructorDescriptorExpression.isBlank()
                && Pattern.compile(constructorDescriptorExpression).matcher(descriptor).matches();
    }

    private static JsonNode schema() throws Exception {
        String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
        return MAPPER.readTree(Path.of(
                root, "schemas", "analysis", "method-catalog-v1.schema.json").toFile());
    }
}
