package org.example.algorithmdebug.normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdwpSnapshotNormalizerTest {

    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final RunId RUN_ID = new RunId("run-1");
    private static final PlanId PLAN_ID = new PlanId("plan-1");
    private static final CollectionId COLLECTION_ID = new CollectionId("collection-1");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-1");
    private static final TargetTest TARGET = new TargetTest("fixture.AlgorithmTest", "runs");
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private int fileCounter;

    @TempDir
    Path temporaryDirectory;

    @Test
    void lifecycleOnlyTraceIsPartialAndDoesNotInventRuntimeEvidence() throws Exception {
        Path raw = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"collector_started"}
                {"schemaVersion":"1.0","sessionId":"s","sequence":2,"timestamp":"2026-08-18T00:00:01Z","eventType":"collector_finished"}
                """);

        NormalizationResult<JdwpSnapshotSummary> result = normalize(raw, stackOnlyPlan("point-1"),
                NormalizationBudget.defaults(), false);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.summary().orElseThrow().hits().isEmpty());
        assertTrue(result.truncationReasons().contains("ZERO_TRACEPOINT_HITS"));
        assertFalse(result.summary().orElseThrow().truncated());
    }

    @Test
    void normalizesStackHitsInSequenceOrderWithExactProvenance() throws Exception {
        Path raw = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"collector_started"}
                {"schemaVersion":"1.0","sessionId":"s","sequence":2,"timestamp":"2026-08-18T00:00:01Z","eventType":"tracepoint_hit","tracepointId":"point-1","hit":1,"thread":{"id":1,"name":"main"},"location":{"className":"fixture.Algorithm","methodName":"solve","line":12,"codeIndex":4},"frames":[{"index":0,"className":"fixture.Algorithm","methodName":"solve","line":12},{"index":1,"className":"fixture.AlgorithmTest","methodName":"runs","line":30}]}
                {"schemaVersion":"1.0","sessionId":"s","sequence":3,"timestamp":"2026-08-18T00:00:02Z","eventType":"tracepoint_hit","tracepointId":"point-1","hit":2,"thread":{"id":2,"name":"worker"},"location":{"className":"fixture.Algorithm","methodName":"solve","line":12,"codeIndex":4},"frames":[{"index":0,"className":"fixture.Algorithm","methodName":"solve","line":12}]}
                {"schemaVersion":"1.0","sessionId":"s","sequence":4,"timestamp":"2026-08-18T00:00:03Z","eventType":"collector_finished"}
                """);

        NormalizationResult<JdwpSnapshotSummary> result = normalize(raw, stackOnlyPlan("point-1"),
                NormalizationBudget.defaults(), false);

        assertEquals(NormalizationStatus.COMPLETE, result.status());
        List<JdwpSnapshotSummary.TracepointHit> hits = result.summary().orElseThrow().hits();
        assertEquals(List.of(1, 2), hits.stream().map(JdwpSnapshotSummary.TracepointHit::hit).toList());
        assertEquals("worker", hits.get(1).threadName());
        assertEquals(2, hits.getFirst().frames().size());
        assertEquals(2, hits.getFirst().provenance().jsonlLine());
        assertEquals(Optional.of(2L), hits.getFirst().provenance().sequence());
        assertTrue(hits.stream().allMatch(hit -> hit.values().isEmpty()));
        assertEquals(5, result.emittedFactCount());
    }

    @Test
    void flattensCapturedValuesAndPreservesCollectorMarkersWithoutNameFiltering() throws Exception {
        Path raw = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"tracepoint_hit","tracepointId":"point-1","hit":1,"thread":{"id":1,"name":"main"},"location":{"className":"fixture.Algorithm","methodName":"solve","line":12,"codeIndex":4},"frames":[{"index":0,"className":"fixture.Algorithm","methodName":"solve","line":12,"locals":{"count":7,"context":{"$type":"fixture.Context","$id":9,"fields":{"job":{"$type":"fixture.Job","$id":10,"fields":{"jobId":"JOB-7"}},"scores":{"$type":"int[]","$id":11,"$length":3,"elements":[1,2],"$remaining":1}}}},"this":{"$type":"fixture.Algorithm","$id":1,"$truncated":true,"$remainingFields":2}}]}
                """);

        NormalizationResult<JdwpSnapshotSummary> result = normalize(raw, plan("point-1"),
                NormalizationBudget.defaults(), false);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.truncationReasons().contains("COLLECTOR_VALUE_LIMIT"));
        JdwpSnapshotSummary.TracepointHit hit = result.summary().orElseThrow().hits().getFirst();
        assertEquals("7", value(hit, "locals.count").scalarPreview());
        assertEquals("JOB-7", value(hit,
                "locals.context.fields.job.fields.jobId").scalarPreview());
        assertEquals("1", value(hit,
                "locals.context.fields.scores.elements[0]").scalarPreview());
        assertTrue(value(hit, "locals.context").collectorMarkers().contains("$id=9"));
        List<String> markers = result.summary().orElseThrow().limits().stream()
                .map(JdwpSnapshotSummary.CollectorLimitFact::marker).toList();
        assertTrue(markers.contains("$remaining"));
        assertTrue(markers.contains("$truncated"));
        assertTrue(markers.contains("$remainingFields"));
        assertTrue(hit.values().stream().allMatch(fact -> fact.provenance().jsonlLine() == 1));
    }

    @Test
    void truncatesScalarPreviewOnlyByConfiguredLength() throws Exception {
        Path raw = write(hit(1, "point-1", 1,
                "\"algorithm-state-that-is-long\""));
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget budget = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), defaults.maxValueFacts(), 8,
                defaults.maxSummaryBytes());

        JdwpSnapshotSummary.ValueFact fact = normalize(raw, plan("point-1"), budget, false)
                .summary().orElseThrow().hits().getFirst().values().getFirst();

        assertEquals("algorith", fact.scalarPreview());
        assertTrue(fact.previewTruncated());
    }

    @Test
    void preservesPrimitiveCycleErrorAndCollectedFacts() throws Exception {
        Path raw = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"tracepoint_hit","tracepointId":"point-1","hit":1,"thread":{"id":1,"name":"main"},"location":{"className":"fixture.Algorithm","methodName":"solve","line":12,"codeIndex":4},"frames":[{"index":0,"className":"fixture.Algorithm","methodName":"solve","line":12,"locals":{"enabled":true,"ratio":1.5,"missing":null,"fields":"local-named-fields","elements":"local-named-elements","cycle":{"$type":"fixture.Node","$id":3,"$cycle":3},"failed":{"$type":"fixture.Node","$error":"field read failed","$collected":true}}}]}
                """);

        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                raw, plan("point-1"), NormalizationBudget.defaults(), false);
        JdwpSnapshotSummary.TracepointHit hit = result.summary().orElseThrow().hits().getFirst();

        assertEquals("BOOLEAN", value(hit, "locals.enabled").kind());
        assertEquals("DECIMAL", value(hit, "locals.ratio").kind());
        assertEquals("NULL", value(hit, "locals.missing").kind());
        assertEquals("local-named-fields", value(hit, "locals.fields").scalarPreview());
        assertEquals("local-named-elements", value(hit, "locals.elements").scalarPreview());
        assertTrue(value(hit, "locals.cycle").collectorMarkers().contains("$cycle=3"));
        assertTrue(value(hit, "locals.failed").collectorMarkers().contains("$collected=true"));
        assertTrue(result.summary().orElseThrow().limits().stream().anyMatch(
                limit -> "$error".equals(limit.marker())
                        && "field read failed".equals(limit.detail())));
        assertEquals(NormalizationStatus.PARTIAL, result.status());
    }

    @Test
    void boundsHitValueAndSummaryOutputIndependently() throws Exception {
        Path twoHits = write(
                hit(1, "point-1", 1, "\"first-state\"")
                        + hit(2, "point-1", 2, "\"second-state\""));
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget oneHit = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), 1,
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), defaults.maxSummaryBytes());
        NormalizationResult<JdwpSnapshotSummary> hitLimited = normalize(
                twoHits, plan("point-1"), oneHit, false);
        assertEquals(1, hitLimited.summary().orElseThrow().hits().size());
        assertTrue(hitLimited.truncationReasons().contains("HIT_BUDGET_EXCEEDED"));

        Path threeValues = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"tracepoint_hit","tracepointId":"point-1","hit":1,"thread":{"id":1,"name":"main"},"location":{"className":"fixture.Algorithm","methodName":"solve","line":12,"codeIndex":4},"frames":[{"index":0,"className":"fixture.Algorithm","methodName":"solve","line":12,"locals":{"one":1,"two":2,"three":3}}]}
                """);
        NormalizationBudget twoValues = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), 2, defaults.maxScalarChars(),
                defaults.maxSummaryBytes());
        NormalizationResult<JdwpSnapshotSummary> valueLimited = normalize(
                threeValues, plan("point-1"), twoValues, false);
        assertEquals(2, valueLimited.summary().orElseThrow().hits().getFirst().values().size());
        assertTrue(valueLimited.truncationReasons().contains("VALUE_BUDGET_EXCEEDED"));

        NormalizationBudget outputLimited = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), 5_000);
        NormalizationResult<JdwpSnapshotSummary> output = normalize(
                threeValues, plan("point-1"), outputLimited, false);
        assertEquals(NormalizationStatus.PARTIAL, output.status());
        assertTrue(output.truncationReasons().contains("OUTPUT_BUDGET_EXCEEDED"));
        assertTrue(output.summary().orElseThrow().truncated());

        NormalizationBudget impossibleOutput = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), 1);
        NormalizationResult<JdwpSnapshotSummary> impossible = normalize(
                threeValues, plan("point-1"), impossibleOutput, false);
        assertEquals(NormalizationStatus.FAILED, impossible.status());
        assertEquals("NORMALIZE_OUTPUT_BUDGET_TOO_SMALL",
                impossible.failureCode().orElseThrow());
        assertTrue(impossible.summary().isEmpty());
    }

    @Test
    void sequenceGapAndFrameBudgetProducePartialTruncatedSummary() throws Exception {
        Path raw = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":2,"timestamp":"2026-08-18T00:00:00Z","eventType":"tracepoint_hit","tracepointId":"point-1","hit":1,"thread":{"id":1,"name":"main"},"location":{"className":"fixture.Algorithm","methodName":"solve","line":12,"codeIndex":4},"frames":[{"index":0,"className":"fixture.Algorithm","methodName":"solve","line":12},{"index":1,"className":"fixture.AlgorithmTest","methodName":"runs","line":30}]}
                {"schemaVersion":"1.0","sessionId":"s","sequence":4,"timestamp":"2026-08-18T00:00:01Z","eventType":"collector_finished"}
                """);
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget budget = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(), 1,
                defaults.maxValueFacts(), defaults.maxScalarChars(), defaults.maxSummaryBytes());

        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                raw, plan("point-1"), budget, false);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.truncationReasons().contains("SEQUENCE_INCOMPLETE"));
        assertTrue(result.truncationReasons().contains("FRAME_BUDGET_EXCEEDED"));
        assertEquals(1, result.summary().orElseThrow().hits().getFirst().frames().size());
        assertTrue(result.summary().orElseThrow().truncated());
    }

    @Test
    void unknownLifecycleAndMissingHitLocationAreStructuredFailures() throws Exception {
        Path unknown = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"collector_paused"}
                """);
        assertEquals("NORMALIZE_SCHEMA_UNSUPPORTED", normalize(
                unknown, plan("point-1"), NormalizationBudget.defaults(), false)
                .failureCode().orElseThrow());

        Path missingLocation = write("""
                {"schemaVersion":"1.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"tracepoint_hit","tracepointId":"point-1","hit":1,"thread":{"id":1,"name":"main"},"frames":[]}
                """);
        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                missingLocation, plan("point-1"), NormalizationBudget.defaults(), false);
        assertEquals(NormalizationStatus.FAILED, result.status());
        assertEquals("NORMALIZE_SCHEMA_UNSUPPORTED", result.failureCode().orElseThrow());
        assertTrue(result.summary().isEmpty());
    }

    @Test
    void rejectsCapturedLocalsThatWereNotRequestedByThePlan() throws Exception {
        Path raw = write(hit(1, "point-1", 1, "\"unexpected-state\""));

        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                raw, stackOnlyPlan("point-1"), NormalizationBudget.defaults(), false);

        assertEquals(NormalizationStatus.FAILED, result.status());
        assertEquals("NORMALIZE_EVENT_OUTSIDE_PLAN", result.failureCode().orElseThrow());
        assertTrue(result.summary().isEmpty());
    }

    private NormalizationResult<JdwpSnapshotSummary> normalize(
            Path raw,
            JdwpCollectionPlan plan,
            NormalizationBudget budget,
            boolean collectorTruncated) throws Exception {
        return new JdwpSnapshotNormalizer().normalize(new JdwpNormalizationInput(
                new JdwpCollectionRecord(
                        SchemaVersions.JDWP_COLLECTION_REQUEST, CASE_ID, CONTEXT_ID,
                        ANALYSIS_ID, RUN_ID, PLAN_ID, COLLECTION_ID, TARGET, "JDWP", NOW),
                plan,
                new ArtifactReference(
                        "raw-jdwp", "JDWP_RAW_TRACE",
                        "collections/collection-1/raw/raw-trace.jsonl",
                        "application/x-ndjson", HASH, Files.size(raw)),
                raw, EVIDENCE_ID, budget, collectorTruncated, NOW));
    }

    private Path write(String content) throws Exception {
        Path path = temporaryDirectory.resolve("raw-" + fileCounter++ + ".jsonl");
        Files.writeString(path, content);
        return path;
    }

    private static JdwpCollectionPlan plan(String... ids) {
        return plan(new JdwpCaptureSpec(true, true, 8, 2, 20, 256), ids);
    }

    private static JdwpCollectionPlan stackOnlyPlan(String... ids) {
        return plan(JdwpCaptureSpec.stackOnly(), ids);
    }

    private static JdwpCollectionPlan plan(JdwpCaptureSpec capture, String... ids) {
        List<JdwpTracepointSpec> points = Arrays.stream(ids).map(id -> new JdwpTracepointSpec(
                id, "fixture.Algorithm#solve()V",
                new SourceAnchor(
                        "fixture.Algorithm", "solve", "()V",
                        "src/main/java/fixture/Algorithm.java", 10, 20, HASH),
                12, capture.locals() ? 5 : 20, capture)).toList();
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN, PLAN_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, TARGET, points, JdwpCollectionBudget.defaults(),
                "定位方法内部状态", NOW);
    }

    private static String hit(long sequence, String tracepointId, int hit, String localValue) {
        return "{\"schemaVersion\":\"1.0\",\"sessionId\":\"s\",\"sequence\":" + sequence
                + ",\"timestamp\":\"2026-08-18T00:00:00Z\",\"eventType\":\"tracepoint_hit\""
                + ",\"tracepointId\":\"" + tracepointId + "\",\"hit\":" + hit
                + ",\"thread\":{\"id\":1,\"name\":\"main\"},\"location\":{\"className\":\"fixture.Algorithm\",\"methodName\":\"solve\",\"line\":12,\"codeIndex\":4}"
                + ",\"frames\":[{\"index\":0,\"className\":\"fixture.Algorithm\",\"methodName\":\"solve\",\"line\":12,\"locals\":{\"algorithmState\":"
                + localValue + "}}]}\n";
    }

    private static JdwpSnapshotSummary.ValueFact value(
            JdwpSnapshotSummary.TracepointHit hit,
            String path) {
        return hit.values().stream().filter(fact -> path.equals(fact.valuePath()))
                .findFirst().orElseThrow();
    }
}
