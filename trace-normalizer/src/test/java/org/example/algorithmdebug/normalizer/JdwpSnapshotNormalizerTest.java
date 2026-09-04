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
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.InvestigationIntent;
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
                {"schemaVersion":"3.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"collector_started"}
                {"schemaVersion":"3.0","sessionId":"s","sequence":2,"timestamp":"2026-08-18T00:00:01Z","eventType":"collector_finished"}
                """);

        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                raw, plan(JdwpCaptureSpec.stackOnly(), 1, 0),
                NormalizationBudget.defaults(), false);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.summary().orElseThrow().hits().isEmpty());
        assertTrue(result.truncationReasons().contains("ZERO_TRACEPOINT_HITS"));
        assertFalse(result.summary().orElseThrow().truncated());
    }

    @Test
    void normalizesStackHitsInSequenceOrderWithExactProvenance() throws Exception {
        Path raw = write(
                lifecycle("collector_started", 1)
                        + hit(3, 2, 2, 2, frames(2), "[]")
                        + hit(2, 1, 1, 1, frames(1), "[]")
                        + lifecycle("collector_finished", 4));

        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                raw, plan(JdwpCaptureSpec.stackOnly(), 2, 0),
                NormalizationBudget.defaults(), false);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        List<JdwpSnapshotSummary.TracepointHit> hits = result.summary().orElseThrow().hits();
        assertEquals(List.of(1, 2), hits.stream()
                .map(JdwpSnapshotSummary.TracepointHit::capturedHit).toList());
        assertEquals("worker", hits.get(1).threadName());
        assertEquals(Optional.of("()V"), hits.getFirst().methodDescriptor());
        assertEquals(Optional.of(4L), hits.getFirst().codeIndex());
        assertEquals(3, hits.getFirst().provenance().jsonlLine());
        assertEquals(Optional.of(2L), hits.getFirst().provenance().sequence());
        assertTrue(result.truncationReasons().contains("SEQUENCE_INCOMPLETE"));
    }

    @Test
    void preservesEveryPlannedProjectionStatusInPlanOrder() throws Exception {
        JdwpCaptureSpec capture = new JdwpCaptureSpec(
                false, 8, 16,
                List.of("algorithmState", "candidate.wafer.id", "candidate", "missing"));
        Path raw = write(hit(1, 4, 3, 2, noFrames(), """
                [
                  {"valuePath":"candidate","status":"REFERENCE_ONLY","kind":"OBJECT","runtimeType":"fixture.Candidate","valueTruncated":false,"objectId":9,"reason":"DEEPER_PATH_REQUIRED"},
                  {"valuePath":"missing","status":"UNAVAILABLE","valueTruncated":false,"reason":"LOCAL_NOT_FOUND"},
                  {"valuePath":"candidate.wafer.id","status":"TRUNCATED","kind":"STRING","runtimeType":"java.lang.String","scalarValue":"WAFER-12345","valueTruncated":true,"objectId":10,"reason":"STRING_LIMIT"},
                  {"valuePath":"algorithmState","status":"CAPTURED","kind":"INTEGER","runtimeType":"int","scalarValue":"7","valueTruncated":false}
                ]
                """));

        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                raw, plan(capture, 3, 0), NormalizationBudget.defaults(), false);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        JdwpSnapshotSummary.TracepointHit hit = result.summary().orElseThrow().hits().getFirst();
        assertEquals(List.of("algorithmState", "candidate.wafer.id", "candidate", "missing"),
                hit.projections().stream().map(JdwpSnapshotSummary.ProjectionFact::valuePath).toList());
        assertEquals(JdwpSnapshotSummary.ProjectionStatus.CAPTURED,
                projection(hit, "algorithmState").status());
        assertEquals(Optional.of("7"), projection(hit, "algorithmState").scalarValue());
        assertEquals(JdwpSnapshotSummary.ProjectionStatus.TRUNCATED,
                projection(hit, "candidate.wafer.id").status());
        assertEquals(JdwpSnapshotSummary.ProjectionStatus.REFERENCE_ONLY,
                projection(hit, "candidate").status());
        assertEquals(Optional.of(9L), projection(hit, "candidate").objectId());
        assertEquals(JdwpSnapshotSummary.ProjectionStatus.UNAVAILABLE,
                projection(hit, "missing").status());
        assertTrue(result.truncationReasons().contains("PROJECTION_TRUNCATED"));
        assertTrue(result.truncationReasons().contains("PROJECTION_REQUIRES_DEEPER_PATH"));
        assertTrue(result.truncationReasons().contains("PROJECTION_UNAVAILABLE"));
    }

    @Test
    void rejectsMissingOrUnplannedProjectionPaths() throws Exception {
        JdwpCaptureSpec capture = new JdwpCaptureSpec(
                false, 8, 64, List.of("algorithmState", "candidate.wafer.id"));
        Path missing = write(hit(1, 1, 1, 1, noFrames(), """
                [{"valuePath":"algorithmState","status":"CAPTURED","kind":"INTEGER","runtimeType":"int","scalarValue":"7","valueTruncated":false}]
                """));
        NormalizationResult<JdwpSnapshotSummary> missingResult = normalize(
                missing, plan(capture, 1, 0), NormalizationBudget.defaults(), false);
        assertEquals(NormalizationStatus.FAILED, missingResult.status());
        assertEquals("NORMALIZE_SCHEMA_UNSUPPORTED", missingResult.failureCode().orElseThrow());

        Path unplanned = write(hit(1, 1, 1, 1, noFrames(), """
                [
                  {"valuePath":"algorithmState","status":"CAPTURED","kind":"INTEGER","runtimeType":"int","scalarValue":"7","valueTruncated":false},
                  {"valuePath":"candidate.wafer.id","status":"CAPTURED","kind":"STRING","runtimeType":"java.lang.String","scalarValue":"W1","valueTruncated":false},
                  {"valuePath":"other","status":"CAPTURED","kind":"INTEGER","runtimeType":"int","scalarValue":"9","valueTruncated":false}
                ]
                """));
        NormalizationResult<JdwpSnapshotSummary> unplannedResult = normalize(
                unplanned, plan(capture, 1, 0), NormalizationBudget.defaults(), false);
        assertEquals(NormalizationStatus.FAILED, unplannedResult.status());
        assertEquals("NORMALIZE_EVENT_OUTSIDE_PLAN", unplannedResult.failureCode().orElseThrow());
    }

    @Test
    void appliesBudgetsWithoutWritingPartialHits() throws Exception {
        JdwpCaptureSpec capture = new JdwpCaptureSpec(
                false, 8, 64, List.of("algorithmState"));
        String projection = """
                [{"valuePath":"algorithmState","status":"CAPTURED","kind":"INTEGER","runtimeType":"int","scalarValue":"7","valueTruncated":false}]
                """;
        Path raw = write(hit(1, 1, 1, 1, noFrames(), projection)
                + hit(2, 2, 2, 2, noFrames(), projection));
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget oneProjection = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), 1, defaults.maxScalarChars(),
                defaults.maxSummaryBytes());

        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                raw, plan(capture, 2, 0), oneProjection, false);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertEquals(1, result.summary().orElseThrow().hits().size());
        assertEquals(1, result.summary().orElseThrow().hits().getFirst().projections().size());
        assertTrue(result.truncationReasons().contains("PROJECTION_BUDGET_EXCEEDED"));
    }

    @Test
    void rejectsUnsupportedRawVersionAndSamplingViolations() throws Exception {
        Path oldVersion = write("""
                {"schemaVersion":"2.0","sessionId":"s","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"collector_started"}
                """);
        assertEquals("NORMALIZE_SCHEMA_UNSUPPORTED", normalize(
                oldVersion, plan(JdwpCaptureSpec.stackOnly(), 1, 0),
                NormalizationBudget.defaults(), false).failureCode().orElseThrow());

        Path unsampled = write(hit(1, 2, 2, 1, noFrames(), "[]"));
        NormalizationResult<JdwpSnapshotSummary> result = normalize(
                unsampled, plan(JdwpCaptureSpec.stackOnly(), 1, 3),
                NormalizationBudget.defaults(), false);
        assertEquals(NormalizationStatus.FAILED, result.status());
        assertEquals("NORMALIZE_EVENT_OUTSIDE_PLAN", result.failureCode().orElseThrow());
    }

    private NormalizationResult<JdwpSnapshotSummary> normalize(
            Path raw,
            JdwpCollectionPlan plan,
            NormalizationBudget budget,
            boolean collectorTruncated) throws Exception {
        return new JdwpSnapshotNormalizer().normalize(new JdwpNormalizationInput(
                new JdwpCollectionRecord(
                        SchemaVersions.JDWP_COLLECTION_REQUEST, CASE_ID, ANALYSIS_ID,
                        RUN_ID, PLAN_ID, COLLECTION_ID, TARGET, "JDWP", NOW),
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

    private static JdwpCollectionPlan plan(
            JdwpCaptureSpec capture, int captureFirstMatchedHits, int captureEveryMatchedHits) {
        List<JdwpTracepointSpec> points = Arrays.stream(new String[] {"point-1"})
                .map(id -> new JdwpTracepointSpec(
                        id, "fixture.Algorithm#solve()V",
                        new SourceAnchor(
                                "fixture.Algorithm", "solve", "()V",
                                "src/main/java/fixture/Algorithm.java", 10, 20),
                        12, 20, 20, captureFirstMatchedHits, captureEveryMatchedHits,
                        List.of(), capture))
                .toList();
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN, PLAN_ID, CASE_ID, ANALYSIS_ID,
                TARGET, points, JdwpCollectionBudget.defaults(), "Inspect method state",
                new InvestigationIntent(
                        "Which state was observed?",
                        "The target method receives the expected state",
                        List.of(), List.of("A matching runtime snapshot")),
                NOW);
    }

    private static String lifecycle(String eventType, long sequence) {
        return "{\"schemaVersion\":\"3.0\",\"sessionId\":\"s\",\"sequence\":"
                + sequence + ",\"timestamp\":\"2026-08-18T00:00:00Z\",\"eventType\":\""
                + eventType + "\"}\n";
    }

    private static String hit(
            long sequence,
            int observedHit,
            int matchedHit,
            int capturedHit,
            String frames,
            String projections) {
        return "{\"schemaVersion\":\"3.0\",\"sessionId\":\"s\",\"sequence\":"
                + sequence + ",\"timestamp\":\"2026-08-18T00:00:00Z\","
                + "\"eventType\":\"tracepoint_hit\",\"tracepointId\":\"point-1\","
                + "\"observedHit\":" + observedHit + ",\"matchedHit\":" + matchedHit
                + ",\"capturedHit\":" + capturedHit + ","
                + "\"thread\":{\"id\":1,\"name\":\""
                + (capturedHit == 2 ? "worker" : "main") + "\"},"
                + "\"location\":{\"className\":\"fixture.Algorithm\","
                + "\"methodName\":\"solve\",\"methodDescriptor\":\"()V\","
                + "\"line\":12,\"codeIndex\":4},\"frames\":" + frames
                + ",\"projections\":"
                + projections.lines().map(String::strip).reduce("", String::concat)
                + "}\n";
    }

    private static String noFrames() {
        return "[]";
    }

    private static String frames(int frameCount) {
        String first = """
                {"index":0,"className":"fixture.Algorithm","methodName":"solve","methodDescriptor":"()V","line":12,"codeIndex":4}
                """.strip();
        if (frameCount == 1) return "[" + first + "]";
        String second = """
                {"index":1,"className":"fixture.AlgorithmTest","methodName":"runs","methodDescriptor":"()V","line":30,"codeIndex":8}
                """.strip();
        return "[" + first + "," + second + "]";
    }

    private static JdwpSnapshotSummary.ProjectionFact projection(
            JdwpSnapshotSummary.TracepointHit hit, String path) {
        return hit.projections().stream()
                .filter(fact -> path.equals(fact.valuePath()))
                .findFirst()
                .orElseThrow();
    }
}
