package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.example.algorithmdebug.contracts.EvidenceQueryFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredEvidenceQueryTest {
    @TempDir Path temporaryDirectory;
    private CaseArchiveRepository repository;
    private CaseArtifactAccess artifacts;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        repository = new CaseArchiveRepository(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
        repository.createCase(CaseArchiveRepositoryTest.manifest());
        artifacts = new CaseArtifactAccess(casesRoot);
    }

    @Test
    void queriesCodePathInvocationsByMethodAndProjectionValue() throws Exception {
        Path file = register("codepath-invocations", "CODEPATH_INVOCATIONS", "application/x-ndjson", """
                {"sequence":1,"methodRef":"fixture.Algorithm#schedule()V","projections":[{"name":"waferId","path":"arg[0].id","status":"VALUE","required":true,"value":"W1"}]}
                {"sequence":2,"methodRef":"fixture.Algorithm#schedule()V","projections":[{"name":"waferId","path":"arg[0].id","status":"VALUE","required":true,"value":"W2"}]}
                {"sequence":3,"methodRef":"fixture.Other#run()V","projections":[{"name":"waferId","path":"arg[0].id","status":"VALUE","required":true,"value":"W2"}]}
                """);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "codepath-invocations",
                new EvidenceQueryFilter(
                        Optional.of("fixture.Algorithm#schedule()V"), Optional.empty(),
                        Optional.of("waferId"), Optional.of("W2"), Optional.of("VALUE"),
                        Optional.empty(), Optional.empty()),
                0, 20, 65_536);

        assertEquals("CODEPATH_INVOCATION", result.recordType());
        assertEquals(3, result.scannedRecords());
        assertEquals(1, result.matchedRecords());
        assertEquals(1, result.returnedRecords());
        assertTrue(result.recordsJsonl().contains("\"sequence\":2"));
        assertTrue(result.recordsJsonl().contains("\"W2\""));
        assertEquals(Files.size(file), result.artifact().sizeBytes());
    }

    @Test
    void queriesJdwpHitsByTracepointAndValuePathWithPagination() throws Exception {
        register("jdwp-summary", "JDWP_SNAPSHOT_SUMMARY", "application/json", """
                {"hits":[
                  {"tracepointId":"point-1","capturedHit":1,"projections":[{"valuePath":"waferId","status":"CAPTURED","scalarValue":"W1"}],"provenance":{"sequence":7}},
                  {"tracepointId":"point-1","capturedHit":2,"projections":[{"valuePath":"waferId","status":"CAPTURED","scalarValue":"W2"}],"provenance":{"sequence":8}},
                  {"tracepointId":"point-2","capturedHit":1,"projections":[{"valuePath":"waferId","status":"CAPTURED","scalarValue":"W2"}],"provenance":{"sequence":9}}
                ]}
                """);

        var filter = new EvidenceQueryFilter(
                Optional.empty(), Optional.of("point-1"), Optional.of("waferId"),
                Optional.empty(), Optional.of("CAPTURED"), Optional.of(7L), Optional.of(8L));
        var first = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "jdwp-summary",
                filter, 0, 1, 65_536);
        var second = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "jdwp-summary",
                filter, 1, 1, 65_536);

        assertEquals("JDWP_SNAPSHOT", first.recordType());
        assertEquals(2, first.matchedRecords());
        assertEquals(1, first.returnedRecords());
        assertTrue(first.truncated());
        assertTrue(first.recordsJsonl().contains("\"capturedHit\":1"));
        assertTrue(second.recordsJsonl().contains("\"capturedHit\":2"));
        assertTrue(!second.truncated());
    }

    @Test
    void rejectsUnsupportedOrChangedArtifacts() throws Exception {
        register("plain-log", "TARGET_STDOUT", "text/plain", "text");
        WorkspaceException unsupported = assertThrows(WorkspaceException.class, () ->
                new RegisteredEvidenceQuery(repository).query(
                        CaseArchiveRepositoryTest.manifest().caseId(), "plain-log",
                        EvidenceQueryFilter.none(), 0, 20, 65_536));
        assertEquals("CASE_EVIDENCE_QUERY_ARTIFACT_UNSUPPORTED", unsupported.code());

        Path changed = register(
                "changed-codepath", "CODEPATH_INVOCATIONS", "application/x-ndjson",
                "{\"sequence\":1,\"methodRef\":\"A#m()V\",\"projections\":[]}\n");
        Files.writeString(changed, "{\"changed\":true}\n");
        WorkspaceException integrity = assertThrows(WorkspaceException.class, () ->
                new RegisteredEvidenceQuery(repository).query(
                        CaseArchiveRepositoryTest.manifest().caseId(), "changed-codepath",
                        EvidenceQueryFilter.none(), 0, 20, 65_536));
        assertEquals("CASE_ARTIFACT_INTEGRITY_MISMATCH", integrity.code());
    }

    @Test
    void byteTruncationNeverSkipsARecordAndReturnsLaterRows() throws Exception {
        register("ordered-invocations", "CODEPATH_INVOCATIONS", "application/x-ndjson", """
                {"sequence":1,"methodRef":"A#m()V","projections":[]}
                {"sequence":2,"methodRef":"A#m()V","padding":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx","projections":[]}
                {"sequence":3,"methodRef":"A#m()V","projections":[]}
                """);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "ordered-invocations",
                EvidenceQueryFilter.none(), 0, 20, 100);

        assertEquals(3, result.matchedRecords());
        assertEquals(1, result.returnedRecords());
        assertTrue(result.truncated());
        assertTrue(result.recordsJsonl().contains("\"sequence\":1"));
        assertTrue(!result.recordsJsonl().contains("\"sequence\":3"));
    }

    private Path register(
            String artifactId, String artifactType, String mediaType, String content) throws Exception {
        Path file = repository.layout(CaseArchiveRepositoryTest.manifest().caseId())
                .caseRoot().resolve(artifactId + (mediaType.endsWith("ndjson") ? ".jsonl" : ".json"));
        Files.writeString(file, content);
        var reference = artifacts.describe(
                CaseArchiveRepositoryTest.manifest().caseId(), artifactId, artifactType,
                mediaType, file);
        repository.registerArtifact(
                CaseArchiveRepositoryTest.manifest().caseId(), reference,
                Instant.parse("2026-09-03T00:00:00Z"));
        return file;
    }
}
