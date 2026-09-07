package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.example.algorithmdebug.contracts.EvidenceQueryFilter;
import org.example.algorithmdebug.contracts.EvidenceQueryGroupBy;
import org.example.algorithmdebug.contracts.EvidenceQueryMode;
import org.example.algorithmdebug.contracts.EvidenceQueryRequest;
import org.example.algorithmdebug.contracts.EvidenceValuePredicate;
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

    @Test
    void summarizesAvailableCodePathDimensionsBeforeTheModelFilters() throws Exception {
        register("summary-invocations", "CODEPATH_INVOCATIONS", "application/x-ndjson", """
                {"sequence":10,"methodRef":"A#schedule()V","projections":[{"name":"waferId","status":"VALUE","value":"W1"}]}
                {"sequence":20,"methodRef":"A#schedule()V","projections":[{"name":"waferId","status":"VALUE","value":"W2"}]}
                {"sequence":30,"methodRef":"B#commit()V","projections":[{"name":"result","status":"NULL","value":null}]}
                """);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "summary-invocations",
                EvidenceQueryRequest.summary(65_536));

        assertEquals(EvidenceQueryMode.SUMMARY, result.mode());
        assertEquals(3, result.scannedRecords());
        assertTrue(result.recordsJsonl().contains("A#schedule()V"));
        assertTrue(result.recordsJsonl().contains("waferId"));
        assertTrue(result.recordsJsonl().contains("\"minimumSequence\":10"));
        assertTrue(result.recordsJsonl().contains("\"maximumSequence\":30"));
    }

    @Test
    void queriesAggregateMethodPathSummaryWithoutScanningRawEvents() throws Exception {
        register("aggregate-summary", "METHOD_PATH_SUMMARY", "application/json", """
                {"captureMode":"AGGREGATE","depthSemantics":"SELECTED_METHOD_DEPTH",
                 "sourceCoverage":"PARTIAL","limitations":["VALUE_CARDINALITY_LIMIT_REACHED"],
                 "methods":[{"methodKey":"A#schedule()V","enterCount":10,"exitCount":10,
                   "minDepth":0,"maxDepth":0}],"observedPaths":[],
                 "projectionDistributions":[{"methodKey":"A#schedule()V","projectionName":"entity",
                   "path":"arg[0].id","observedCount":10,"otherCount":3,"distinctLimitReached":true,
                   "trackedValueCounts":[{"status":"VALUE","scalarType":"STRING","value":"E-1",
                     "failureCode":null,"count":7}]}]}
                """);

        var summary = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "aggregate-summary",
                EvidenceQueryRequest.summary(65_536));
        var counts = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "aggregate-summary",
                EvidenceQueryRequest.count(EvidenceQueryFilter.none(),
                        EvidenceQueryGroupBy.PROJECTION_VALUE, Optional.of("entity"), 10, 65_536));

        assertEquals("METHOD_PATH_SUMMARY", summary.recordType());
        assertTrue(summary.recordsJsonl().contains("AGGREGATE"));
        assertTrue(summary.recordsJsonl().contains("VALUE_CARDINALITY_LIMIT_REACHED"));
        assertTrue(counts.recordsJsonl().contains("E-1"));
        assertTrue(counts.recordsJsonl().contains("\"untrackedOtherCount\":3"));
        assertEquals(org.example.algorithmdebug.contracts.EvidenceSourceCoverage.PARTIAL,
                counts.sourceCoverage());
    }

    @Test
    void returnsSupportedModesWhenAggregateSummaryCannotAnswerAWindowQuery() throws Exception {
        register("aggregate-window", "METHOD_PATH_SUMMARY", "application/json", """
                {"captureMode":"AGGREGATE","sourceCoverage":"COMPLETE","limitations":[],
                 "methods":[],"observedPaths":[],"projectionDistributions":[]}
                """);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "aggregate-window",
                EvidenceQueryRequest.window(EvidenceQueryFilter.none(), 1, 1, 1, 65_536));

        assertEquals(org.example.algorithmdebug.contracts.EvidenceQueryOutcome.UNSUPPORTED_MODE,
                result.outcome());
        assertEquals(org.example.algorithmdebug.contracts.EvidenceQueryNextAction.REFINE_QUERY,
                result.nextAction());
        assertTrue(result.recordsJsonl().contains("SUMMARY"));
        assertTrue(result.recordsJsonl().contains("COUNT"));
    }

    @Test
    void aggregateSummaryOmitsDetailsInsteadOfFailingAValidSmallOutputBudget() throws Exception {
        String longMethod = "A".repeat(300) + "#schedule()V";
        register("bounded-aggregate-summary", "METHOD_PATH_SUMMARY", "application/json", """
                {"captureMode":"AGGREGATE","depthSemantics":"SELECTED_METHOD_DEPTH",
                 "sourceCoverage":"COMPLETE","limitations":[],
                 "methods":[{"methodKey":"%s","enterCount":10,"exitCount":10,
                   "minDepth":0,"maxDepth":0},{"methodKey":"%s","enterCount":5,"exitCount":5,
                   "minDepth":1,"maxDepth":1}],"observedPaths":[],
                 "projectionDistributions":[{"methodKey":"%s","projectionName":"entity",
                   "path":"arg[0].id","observedCount":10,"otherCount":0,
                   "distinctLimitReached":false,"trackedValueCounts":[]}]}
                """.formatted(longMethod, longMethod + "2", longMethod));

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "bounded-aggregate-summary",
                EvidenceQueryRequest.summary(1_024));

        assertTrue(result.recordsJsonl().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_024);
        assertTrue(result.recordsJsonl().contains("methodFactsOmitted"));
        assertTrue(result.limitations().contains("SUMMARY_DETAILS_OMITTED_BY_QUERY_BUDGET"));
        assertEquals(org.example.algorithmdebug.contracts.EvidenceQueryNextAction.REFINE_QUERY,
                result.nextAction());
    }

    @Test
    void summaryCountsOnlyRecordsInsideItsStructuralScope() throws Exception {
        register("scoped-summary", "CODEPATH_INVOCATIONS", "application/x-ndjson", """
                {"sequence":1,"methodRef":"A#schedule()V","projections":[]}
                {"sequence":2,"methodRef":"A#schedule()V","projections":[]}
                {"sequence":3,"methodRef":"B#commit()V","projections":[]}
                """);
        EvidenceQueryFilter filter = new EvidenceQueryFilter(
                Optional.of("A#schedule()V"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        EvidenceQueryRequest request = new EvidenceQueryRequest(
                EvidenceQueryMode.SUMMARY, filter, java.util.List.of(), Optional.empty(),
                0, 0, Optional.empty(), Optional.empty(), 10, java.util.List.of(),
                0, 20, 65_536);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "scoped-summary", request);

        assertEquals(3, result.scannedRecords());
        assertEquals(2, result.matchedRecords());
        assertTrue(result.recordsJsonl().contains("\"recordCount\":2"));
    }

    @Test
    void filtersOneRecordWithMultipleProjectionPredicatesUsingAndSemantics() throws Exception {
        register("multi-filter", "CODEPATH_INVOCATIONS", "application/x-ndjson", """
                {"sequence":1,"methodRef":"A#schedule()V","projections":[{"name":"waferId","status":"VALUE","value":"W1"},{"name":"strategy","status":"VALUE","value":"FAST"}]}
                {"sequence":2,"methodRef":"A#schedule()V","projections":[{"name":"waferId","status":"VALUE","value":"W1"},{"name":"strategy","status":"VALUE","value":"SAFE"}]}
                """);
        EvidenceQueryFilter structural = new EvidenceQueryFilter(
                Optional.of("A#schedule()V"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        EvidenceQueryRequest request = EvidenceQueryRequest.filter(
                structural, java.util.List.of(
                        new EvidenceValuePredicate("waferId", Optional.of("W1"), Optional.of("VALUE")),
                        new EvidenceValuePredicate("strategy", Optional.of("SAFE"), Optional.of("VALUE"))),
                0, 20, 65_536);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "multi-filter", request);

        assertEquals(1, result.matchedRecords());
        assertTrue(result.recordsJsonl().contains("\"sequence\":2"));
    }

    @Test
    void returnsRecordsAroundAnAnchorByRecordOrderRatherThanSequenceArithmetic() throws Exception {
        register("window", "CODEPATH_INVOCATIONS", "application/x-ndjson", """
                {"sequence":10,"methodRef":"A#a()V","projections":[]}
                {"sequence":100,"methodRef":"A#b()V","projections":[]}
                {"sequence":1000,"methodRef":"A#c()V","projections":[]}
                {"sequence":10000,"methodRef":"A#d()V","projections":[]}
                """);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "window",
                EvidenceQueryRequest.window(
                        EvidenceQueryFilter.none(), 1000, 1, 1, 65_536));

        assertEquals(EvidenceQueryMode.WINDOW, result.mode());
        assertEquals(3, result.returnedRecords());
        assertTrue(result.recordsJsonl().contains("\"sequence\":100"));
        assertTrue(result.recordsJsonl().contains("\"sequence\":1000"));
        assertTrue(result.recordsJsonl().contains("\"sequence\":10000"));
    }

    @Test
    void countsProjectionValuesAndReportsRowsOutsideTopN() throws Exception {
        register("count", "CODEPATH_INVOCATIONS", "application/x-ndjson", """
                {"sequence":1,"methodRef":"A#m()V","projections":[{"name":"waferId","status":"VALUE","value":"W1"}]}
                {"sequence":2,"methodRef":"A#m()V","projections":[{"name":"waferId","status":"VALUE","value":"W1"}]}
                {"sequence":3,"methodRef":"A#m()V","projections":[{"name":"waferId","status":"VALUE","value":"W2"}]}
                """);

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "count",
                EvidenceQueryRequest.count(EvidenceQueryFilter.none(),
                        EvidenceQueryGroupBy.PROJECTION_VALUE, Optional.of("waferId"), 1, 65_536));

        assertTrue(result.recordsJsonl().contains("\"key\":\"W1\""));
        assertTrue(result.recordsJsonl().contains("\"count\":2"));
        assertTrue(result.recordsJsonl().contains("\"otherCount\":1"));
    }

    @Test
    void derivesOnlyJdwpValueChangesAndExposesSkippedMatchedHits() throws Exception {
        register("changes", "JDWP_SNAPSHOT_SUMMARY", "application/json", """
                {"truncated":false,"limitations":[],"hits":[
                  {"tracepointId":"point-1","observedHit":1,"matchedHit":1,"capturedHit":1,"projections":[{"valuePath":"state","status":"CAPTURED","scalarValue":"READY"}],"provenance":{"sequence":7}},
                  {"tracepointId":"point-1","observedHit":2,"matchedHit":2,"capturedHit":2,"projections":[{"valuePath":"state","status":"CAPTURED","scalarValue":"READY"}],"provenance":{"sequence":8}},
                  {"tracepointId":"point-1","observedHit":6,"matchedHit":5,"capturedHit":3,"projections":[{"valuePath":"state","status":"CAPTURED","scalarValue":"RUNNING"}],"provenance":{"sequence":9}}
                ]}
                """);
        EvidenceQueryFilter filter = new EvidenceQueryFilter(
                Optional.empty(), Optional.of("point-1"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "changes",
                EvidenceQueryRequest.changes(filter, java.util.List.of("state"), 65_536));

        assertEquals(EvidenceQueryMode.CHANGES, result.mode());
        assertEquals(1, result.matchedRecords());
        assertTrue(result.recordsJsonl().contains("\"fromSequence\":8"));
        assertTrue(result.recordsJsonl().contains("\"toSequence\":9"));
        assertTrue(result.recordsJsonl().contains("\"skippedMatchedHits\":2"));
        assertTrue(result.limitations().contains("SAMPLED_CHANGES_DO_NOT_PROVE_INTERMEDIATE_STATES"));
    }

    @Test
    void sampledJdwpValuesCannotProveThatNoIntermediateChangeOccurred() throws Exception {
        register("sampled-no-change", "JDWP_SNAPSHOT_SUMMARY", "application/json", """
                {"truncated":false,"limitations":[],"hits":[
                  {"tracepointId":"point-1","matchedHit":1,"projections":[{"valuePath":"state","status":"CAPTURED","scalarValue":"READY"}],"provenance":{"sequence":1}},
                  {"tracepointId":"point-1","matchedHit":5,"projections":[{"valuePath":"state","status":"CAPTURED","scalarValue":"READY"}],"provenance":{"sequence":2}}
                ]}
                """);
        EvidenceQueryFilter filter = new EvidenceQueryFilter(
                Optional.empty(), Optional.of("point-1"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "sampled-no-change",
                EvidenceQueryRequest.changes(filter, java.util.List.of("state"), 65_536));

        assertEquals(org.example.algorithmdebug.contracts.EvidenceQueryOutcome.NO_MATCH,
                result.outcome());
        assertEquals(org.example.algorithmdebug.contracts.EvidenceSourceCoverage.PARTIAL,
                result.sourceCoverage());
        assertEquals(org.example.algorithmdebug.contracts.EvidenceQueryNextAction.RECOLLECT_WITH_BETTER_SCOPE,
                result.nextAction());
        assertTrue(result.limitations().contains(
                "SAMPLED_CHANGES_DO_NOT_PROVE_INTERMEDIATE_STATES"));
    }

    @Test
    void scansOneHundredThousandCodePathRowsButRejectsAnUnboundedDataset() throws Exception {
        String row = "{\"sequence\":1,\"methodRef\":\"A#m()V\",\"projections\":[]}\n";
        register("bounded-large-trace", "CODEPATH_INVOCATIONS", "application/x-ndjson",
                row.repeat(100_000));
        register("oversized-trace", "CODEPATH_INVOCATIONS", "application/x-ndjson",
                row.repeat(100_001));

        var result = new RegisteredEvidenceQuery(repository).query(
                CaseArchiveRepositoryTest.manifest().caseId(), "bounded-large-trace",
                EvidenceQueryRequest.summary(65_536));
        WorkspaceException failure = assertThrows(WorkspaceException.class, () ->
                new RegisteredEvidenceQuery(repository).query(
                        CaseArchiveRepositoryTest.manifest().caseId(), "oversized-trace",
                        EvidenceQueryRequest.summary(65_536)));

        assertEquals(100_000, result.scannedRecords());
        assertEquals("CASE_EVIDENCE_QUERY_SCAN_LIMIT_EXCEEDED", failure.code());
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
