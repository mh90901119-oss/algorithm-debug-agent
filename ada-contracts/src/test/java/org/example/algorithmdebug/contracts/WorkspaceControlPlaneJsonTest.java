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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceControlPlaneJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant RECORDED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void shouldRoundTripWorkspaceControlPlaneContracts() throws Exception {
        WorkspaceManifest manifest = new WorkspaceManifest(
                SchemaVersions.WORKSPACE_MANIFEST, WorkspaceManifest.KIND, RECORDED_AT);
        WorkspaceInitializationResult initializationResult = new WorkspaceInitializationResult(
                "D:/agent-workspace", true, SchemaVersions.WORKSPACE_MANIFEST);
        ProjectRegistration registration = new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION,
                new ProjectId("algorithm-scheduler-a1b2c3d4e5f6"),
                "algorithm-scheduler",
                "D:/large-system",
                "D:/large-system/algorithm-scheduler",
                "D:/large-system/algorithm-scheduler",
                "pom.xml",
                "MAVEN",
                "output/algorithm-results",
                RECORDED_AT);
        ProjectRegistrationResult registrationResult = new ProjectRegistrationResult(registration, true);
        DoctorReport doctorReport = DoctorReport.fromChecks(List.of(
                new DoctorCheck("java", DoctorStatus.PASS, "JAVA_OK", "Java 21"),
                new DoctorCheck("maven", DoctorStatus.WARN, "MAVEN_VERSION", "Maven version unverified")));

        assertRoundTrip(manifest, WorkspaceManifest.class);
        assertRoundTrip(initializationResult, WorkspaceInitializationResult.class);
        assertRoundTrip(registration, ProjectRegistration.class);
        assertRoundTrip(registrationResult, ProjectRegistrationResult.class);
        assertRoundTrip(doctorReport, DoctorReport.class);
    }

    @Test
    void shouldReadLegacyRegistrationWithoutResultDirectory() throws Exception {
        String legacyJson = """
                {
                  "schemaVersion":"1.0",
                  "projectId":"algorithm-scheduler-a1b2c3d4e5f6",
                  "displayName":"algorithm-scheduler",
                  "repositoryRoot":"D:/large-system",
                  "moduleRoot":"D:/large-system/algorithm-scheduler",
                  "mavenExecutionRoot":"D:/large-system/algorithm-scheduler",
                  "pomPath":"pom.xml",
                  "buildTool":"MAVEN",
                  "registeredAt":"2026-08-16T00:00:00Z"
                }
                """;

        ProjectRegistration registration = MAPPER.readValue(legacyJson, ProjectRegistration.class);

        assertEquals(null, registration.resultJsonDirectory());
    }

    @Test
    void shouldSerializeContractInstantsAsSchemaCompatibleStrings() throws Exception {
        WorkspaceManifest manifest = new WorkspaceManifest(
                SchemaVersions.WORKSPACE_MANIFEST, WorkspaceManifest.KIND, RECORDED_AT);
        ProjectRegistration registration = new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION,
                new ProjectId("algorithm-scheduler-a1b2c3d4e5f6"),
                "algorithm-scheduler",
                "D:/large-system",
                "D:/large-system/algorithm-scheduler",
                "D:/large-system/algorithm-scheduler",
                "pom.xml",
                "MAVEN",
                RECORDED_AT);

        JsonNode manifestJson = MAPPER.readTree(MAPPER.writeValueAsBytes(manifest));
        JsonNode registrationJson = MAPPER.readTree(MAPPER.writeValueAsBytes(registration));

        assertTrue(manifestJson.path("createdAt").isTextual());
        assertEquals("2026-08-16T00:00:00Z", manifestJson.path("createdAt").asText());
        assertTrue(registrationJson.path("registeredAt").isTextual());
        assertEquals("2026-08-16T00:00:00Z", registrationJson.path("registeredAt").asText());
    }

    @Test
    void shouldKeepWorkspaceManifestSchemaAlignedWithContract() throws Exception {
        JsonNode schema = readSchema("workspace-manifest-v1.schema.json");

        assertCommonSchemaShape(
                schema,
                "https://algorithm-debug-agent.local/schemas/workspace/workspace-manifest-v1.schema.json",
                SchemaVersions.WORKSPACE_MANIFEST,
                Set.of("schemaVersion", "kind", "createdAt"));
        assertEquals(WorkspaceManifest.KIND, schema.path("properties").path("kind").path("const").asText());
    }

    @Test
    void shouldKeepProjectRegistrationSchemaAlignedWithContract() throws Exception {
        JsonNode schema = readSchema("project-registration-v1.schema.json");

        assertCommonSchemaShape(
                schema,
                "https://algorithm-debug-agent.local/schemas/workspace/project-registration-v1.schema.json",
                SchemaVersions.PROJECT_REGISTRATION,
                Set.of("schemaVersion", "projectId", "displayName", "repositoryRoot", "moduleRoot",
                        "mavenExecutionRoot", "pomPath", "buildTool", "registeredAt"));
        assertTrue(schema.path("properties").has("resultJsonDirectory"));
        assertEquals("MAVEN", schema.path("properties").path("buildTool").path("const").asText());
    }

    @Test
    void shouldKeepDoctorReportSchemaAlignedWithContractBudget() throws Exception {
        JsonNode schema = readSchema("doctor-report-v1.schema.json");

        assertCommonSchemaShape(
                schema,
                "https://algorithm-debug-agent.local/schemas/workspace/doctor-report-v1.schema.json",
                SchemaVersions.DOCTOR_REPORT,
                Set.of("schemaVersion", "overallStatus", "checks"));
        assertEquals(DoctorReport.MAX_CHECKS,
                schema.path("properties").path("checks").path("maxItems").asInt());
    }

    private static <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        assertEquals(value, MAPPER.readValue(MAPPER.writeValueAsBytes(value), type));
    }

    private static JsonNode readSchema(String fileName) throws Exception {
        String reactorRoot = System.getProperty("maven.multiModuleProjectDirectory", "..");
        Path schemaPath = Path.of(reactorRoot, "schemas", "workspace", fileName);
        return MAPPER.readTree(schemaPath.toFile());
    }

    private static void assertCommonSchemaShape(
            JsonNode schema,
            String expectedId,
            String expectedVersion,
            Set<String> expectedRequired) {
        assertEquals(expectedId, schema.path("$id").asText());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(expectedVersion,
                schema.path("properties").path("schemaVersion").path("const").asText());
        Set<String> actualRequired = new HashSet<>();
        schema.path("required").forEach(node -> actualRequired.add(node.asText()));
        assertEquals(expectedRequired, actualRequired);
    }
}
