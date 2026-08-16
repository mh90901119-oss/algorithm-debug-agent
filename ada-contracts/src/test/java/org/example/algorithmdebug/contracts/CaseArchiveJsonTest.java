package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CaseArchiveJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());
    private static final Instant RECORDED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void shouldRoundTripCaseArchiveContracts() throws Exception {
        CaseId caseId = new CaseId("case-1");
        ContextId contextId = new ContextId("context-1");
        AnalysisId analysisId = new AnalysisId("analysis-1");
        ProjectId projectId = new ProjectId("project-1");
        TargetTest targetTest = new TargetTest("a.b.ScheduleTest", "case1");
        CaseManifest manifest = new CaseManifest(
                SchemaVersions.CASE_MANIFEST, caseId, projectId, targetTest, "问题", RECORDED_AT);
        ContextSnapshot context = new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, caseId, contextId, projectId, targetTest,
                "UNAVAILABLE", new SourceSnapshot("a".repeat(64), 1, 10, SnapshotCompleteness.COMPLETE),
                new InputSnapshot(InputSnapshotStatus.NOT_APPLICABLE, "", "", 0, ""),
                new BuildSnapshot("b".repeat(64), "21", "adapter", "1.0"),
                SnapshotCompleteness.COMPLETE, "c".repeat(64), List.of(), RECORDED_AT);
        AnalysisRequest analysis = new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, caseId, contextId, analysisId, "问题", RECORDED_AT);
        RunRequest run = new RunRequest(
                SchemaVersions.RUN_REQUEST, caseId, contextId, analysisId, new RunId("run-1"),
                targetTest, "UNINSTRUMENTED", RECORDED_AT);
        CaseDigest digest = new CaseDigest(
                SchemaVersions.CASE_DIGEST, caseId, projectId, targetTest,
                Optional.of(contextId), Optional.of(analysisId),
                "问题", Optional.empty(), List.of(), List.of(), List.of(), 1, 1, 0, false);

        assertRoundTrip(manifest, CaseManifest.class);
        assertRoundTrip(context, ContextSnapshot.class);
        assertRoundTrip(analysis, AnalysisRequest.class);
        assertRoundTrip(run, RunRequest.class);
        assertRoundTrip(digest, CaseDigest.class);
    }

    @Test
    void shouldKeepSchemaRequiredFieldsAlignedWithRecords() throws Exception {
        assertSchema("case", "case-manifest-v1.schema.json", SchemaVersions.CASE_MANIFEST,
                Set.of("schemaVersion", "caseId", "projectId", "targetTest", "initialQuestion", "createdAt"));
        assertSchema("case", "context-snapshot-v1.schema.json", SchemaVersions.CONTEXT_SNAPSHOT,
                Set.of("schemaVersion", "caseId", "contextId", "projectId", "targetTest",
                        "repositoryRevision", "sourceSnapshot", "inputSnapshot", "buildSnapshot",
                        "completeness", "fingerprintSha256", "warnings", "createdAt"));
        assertSchema("case", "analysis-request-v1.schema.json", SchemaVersions.ANALYSIS_REQUEST,
                Set.of("schemaVersion", "caseId", "contextId", "analysisId", "question", "createdAt"));
        assertSchema("execution", "run-request-v1.schema.json", SchemaVersions.RUN_REQUEST,
                Set.of("schemaVersion", "caseId", "contextId", "analysisId", "runId", "targetTest",
                        "executionMode", "createdAt"));
        assertSchema("case", "case-digest-v1.schema.json", SchemaVersions.CASE_DIGEST,
                Set.of("schemaVersion", "caseId", "projectId", "targetTest", "latestContextId",
                        "latestAnalysisId", "latestQuestionExcerpt", "latestRunId", "recentRuns",
                        "incompleteRuns", "archiveWarnings", "contextCount", "analysisCount",
                        "runCount", "truncated"));
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
}
