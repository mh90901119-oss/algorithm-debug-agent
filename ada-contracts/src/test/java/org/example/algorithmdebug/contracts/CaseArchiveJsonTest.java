package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseArchiveJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Instant RECORDED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void roundTripsCaseArchiveAndEvidenceQueryContracts() throws Exception {
        CaseId caseId = new CaseId("case-1");
        AnalysisId analysisId = new AnalysisId("analysis-1");
        ProjectId projectId = new ProjectId("project-1");
        TargetTest targetTest = new TargetTest("a.b.ScheduleTest", "case1");
        CaseManifest manifest = new CaseManifest(
                SchemaVersions.CASE_MANIFEST, caseId, projectId, targetTest,
                "maven-junit", "Question", RECORDED_AT);
        AnalysisRequest analysis = new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, caseId, analysisId, "Question", RECORDED_AT);
        ArtifactReference artifact = new ArtifactReference(
                "artifact-1", "CODEPATH_INVOCATIONS", "collections/c1/derived/invocations.jsonl",
                "application/x-ndjson", "a".repeat(64), 6);
        EvidenceQueryResult query = new EvidenceQueryResult(
                SchemaVersions.EVIDENCE_QUERY_RESULT, artifact, "CODEPATH_INVOCATION",
                EvidenceQueryFilter.none(), 1, 1, 0, 20, 1, false, "{}\n");

        assertRoundTrip(manifest, CaseManifest.class);
        assertRoundTrip(analysis, AnalysisRequest.class);
        assertRoundTrip(query, EvidenceQueryResult.class);
        assertFalse(MAPPER.valueToTree(analysis).has("contextId"));
        JsonSchemaTestSupport.assertValid(schemaPath("tool", "evidence-query-result-v1.schema.json"),
                MAPPER.writeValueAsString(query));
    }

    @Test
    void keepsSchemaRequiredFieldsAlignedWithRecords() throws Exception {
        assertSchema("case", "case-manifest-v2.schema.json", SchemaVersions.CASE_MANIFEST,
                Set.of("schemaVersion", "caseId", "projectId", "targetTest", "adapterId",
                        "initialQuestion", "createdAt"));
        assertSchema("case", "analysis-request-v2.schema.json", SchemaVersions.ANALYSIS_REQUEST,
                Set.of("schemaVersion", "caseId", "analysisId", "question", "createdAt"));
        assertSchema("case", "case-digest-v4.schema.json", SchemaVersions.CASE_DIGEST,
                Set.of("schemaVersion", "caseId", "projectId", "targetTest", "latestAnalysisId",
                        "latestQuestionExcerpt", "latestRunId", "recentRuns", "incompleteRuns",
                        "recentCollections", "recentEvidence", "archiveWarnings", "analysisCount",
                        "runCount", "collectionCount", "evidenceCount", "truncated"));
    }

    private static <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        assertEquals(value, MAPPER.readValue(MAPPER.writeValueAsBytes(value), type));
    }

    private static void assertSchema(
            String directory, String fileName, String version, Set<String> required) throws Exception {
        JsonNode schema = MAPPER.readTree(schemaPath(directory, fileName).toFile());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(version, schema.path("properties").path("schemaVersion").path("const").asText());
        Set<String> actual = new HashSet<>();
        schema.path("required").forEach(node -> actual.add(node.asText()));
        assertEquals(required, actual);
    }

    private static Path schemaPath(String directory, String fileName) {
        String reactorRoot = System.getProperty("maven.multiModuleProjectDirectory", "..");
        return Path.of(reactorRoot, "schemas", directory, fileName);
    }
}
