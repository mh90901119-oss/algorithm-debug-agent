package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Instant RECORDED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void shouldRoundTripCaseArchiveContracts() throws Exception {
        CaseId caseId = new CaseId("case-1");
        ContextId contextId = new ContextId("context-1");
        AnalysisId analysisId = new AnalysisId("analysis-1");
        ProjectId projectId = new ProjectId("project-1");
        TargetTest targetTest = new TargetTest("a.b.ScheduleTest", "case1");
        CaseManifest manifest = new CaseManifest(
                SchemaVersions.CASE_MANIFEST, caseId, projectId, targetTest,
                "wafer-demo", "问题", RECORDED_AT);
        ContextRecord context = new ContextRecord(
                SchemaVersions.CONTEXT_RECORD, caseId, contextId, RECORDED_AT);
        AnalysisRequest analysis = new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, caseId, contextId, analysisId, "问题", RECORDED_AT);
        AnalysisResult result = new AnalysisResult(
                SchemaVersions.ANALYSIS_RESULT, caseId, contextId, analysisId, "回答",
                List.of(new AnalysisConclusion(
                        ClaimClassification.LLM_HYPOTHESIS, "仍需验证", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of("缺少运行时状态"), RECORDED_AT);
        RunRequest run = new RunRequest(
                SchemaVersions.RUN_REQUEST, caseId, contextId, analysisId, new RunId("run-1"),
                targetTest, "UNINSTRUMENTED", RECORDED_AT);
        RunResultFingerprint fingerprint = new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, caseId, contextId, new RunId("run-1"),
                "d".repeat(64));
        ArtifactReference artifact = new ArtifactReference(
                "artifact-1", "LOG", "runs/run-1/stdout.log", "text/plain",
                "a".repeat(64), 6);
        CaseArtifactRegistration registration = new CaseArtifactRegistration(
                SchemaVersions.CASE_ARTIFACT_REGISTRATION, caseId, artifact, RECORDED_AT);
        ArtifactTextExcerpt excerpt = new ArtifactTextExcerpt(artifact, 0, 6, false, "output");

        assertRoundTrip(manifest, CaseManifest.class);
        assertRoundTrip(context, ContextRecord.class);
        assertRoundTrip(analysis, AnalysisRequest.class);
        assertRoundTrip(result, AnalysisResult.class);
        assertRoundTrip(run, RunRequest.class);
        assertRoundTrip(fingerprint, RunResultFingerprint.class);
        assertRoundTrip(registration, CaseArtifactRegistration.class);
        assertRoundTrip(excerpt, ArtifactTextExcerpt.class);
        JsonSchemaTestSupport.assertValid(schemaPath("case", "artifact-registration-v1.schema.json"),
                MAPPER.writeValueAsString(registration));
        JsonSchemaTestSupport.assertValid(schemaPath("tool", "artifact-text-excerpt-v1.schema.json"),
                MAPPER.writeValueAsString(excerpt));
    }

    @Test
    void shouldRejectFormerSnapshotJson() {
        String legacy = """
                {"schemaVersion":"1.0","caseId":{"value":"case-1"},
                 "contextId":{"value":"context-1"},"projectId":{"value":"project-1"}}
                """;

        assertThrows(Exception.class, () -> MAPPER.readValue(legacy, ContextRecord.class));
    }

    @Test
    void shouldKeepSchemaRequiredFieldsAlignedWithRecords() throws Exception {
        assertSchema("case", "case-manifest-v2.schema.json", SchemaVersions.CASE_MANIFEST,
                Set.of("schemaVersion", "caseId", "projectId", "targetTest", "adapterId",
                        "initialQuestion", "createdAt"));
        assertSchema("case", "context-record-v2.schema.json", SchemaVersions.CONTEXT_RECORD,
                Set.of("schemaVersion", "caseId", "contextId", "createdAt"));
        assertSchema("case", "analysis-request-v1.schema.json", SchemaVersions.ANALYSIS_REQUEST,
                Set.of("schemaVersion", "caseId", "contextId", "analysisId", "question", "createdAt"));
        assertSchema("case", "analysis-result-v1.schema.json", SchemaVersions.ANALYSIS_RESULT,
                Set.of("schemaVersion", "caseId", "contextId", "analysisId", "finalAnswer",
                        "conclusions", "referencedRunIds", "referencedCollectionIds",
                        "referencedEvidenceIds", "referencedArtifactIds", "missingEvidence",
                        "completedAt"));
        assertSchema("case", "artifact-registration-v1.schema.json",
                SchemaVersions.CASE_ARTIFACT_REGISTRATION,
                Set.of("schemaVersion", "caseId", "artifact", "registeredAt"));
        assertSchema("case", "case-digest-v2.schema.json", SchemaVersions.CASE_DIGEST,
                Set.of("schemaVersion", "caseId", "projectId", "targetTest", "latestContextId",
                        "latestAnalysisId", "latestQuestionExcerpt", "latestRunId", "recentRuns",
                        "incompleteRuns", "recentCollections", "recentEvidence",
                        "recentAnalysisResults", "archiveWarnings", "contextCount", "analysisCount",
                        "runCount", "collectionCount", "evidenceCount", "completedAnalysisCount",
                        "truncated"));
        assertSchema("execution", "run-request-v1.schema.json", SchemaVersions.RUN_REQUEST,
                Set.of("schemaVersion", "caseId", "contextId", "analysisId", "runId", "targetTest",
                        "executionMode", "createdAt"));
        assertSchema("execution", "run-result-fingerprint-v1.schema.json",
                SchemaVersions.RUN_RESULT_FINGERPRINT,
                Set.of("schemaVersion", "caseId", "contextId", "runId", "targetFailureSha256"));
    }

    private static <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        assertEquals(value, MAPPER.readValue(MAPPER.writeValueAsBytes(value), type));
    }

    private static void assertSchema(
            String directory, String fileName, String version, Set<String> required) throws Exception {
        String reactorRoot = System.getProperty("maven.multiModuleProjectDirectory", "..");
        JsonNode schema = MAPPER.readTree(Path.of(reactorRoot, "schemas", directory, fileName).toFile());
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
