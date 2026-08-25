package org.example.algorithmdebug.normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodPathNormalizerTest {

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

    @TempDir
    Path temporaryDirectory;

    @Test
    void summarizesBalancedEventsAsNearestSelectedAncestor() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":3,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":3,"eventType":"METHOD_EXIT","depth":3,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":4,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                """);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve", "choose")), NormalizationBudget.defaults(),
                "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.COMPLETE, result.status());
        MethodPathSummary summary = result.summary().orElseThrow();
        assertEquals(2, summary.methods().size());
        assertEquals(1, summary.observedPaths().size());
        MethodPathSummary.ObservedPath path = summary.observedPaths().getFirst();
        assertEquals("NEAREST_SELECTED_ANCESTOR", path.relationshipType());
        assertEquals("fixture.Algorithm#solve()V", path.ancestorMethodKey());
        assertEquals("fixture.Decision#choose()V", path.descendantMethodKey());
        assertEquals(2, path.firstObservation().jsonlLine());
    }

    @Test
    void preservesStructuralAnomaliesWithoutInventingBalancedTree() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":1,"threadName":"worker","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                """);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve")), NormalizationBudget.defaults(),
                "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        List<String> codes = result.summary().orElseThrow().anomalies().stream()
                .map(MethodPathSummary.PathAnomaly::code).toList();
        assertTrue(codes.contains("UNMATCHED_EXIT"));
        assertTrue(codes.contains("EVENT_ID_NOT_INCREASING"));
        assertEquals(2, codes.stream().filter("OPEN_ENTER_AT_EOF"::equals).count());
        assertFalse(result.summary().orElseThrow().truncated());
    }

    @Test
    void rejectsEventWhenDescriptorIsAbsent() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve"}
                {"eventId":2,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve"}
                """);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve")), NormalizationBudget.defaults(),
                "CLASS_METHOD_SUPERSET", false));

        assertEquals(NormalizationStatus.FAILED, result.status());
        assertEquals("NORMALIZE_SCHEMA_UNSUPPORTED", result.failureCode().orElseThrow());
    }

    @Test
    void marksZeroEventTraceInconclusiveInsteadOfProvingMethodDidNotRun() throws Exception {
        Path trace = write("");

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve")), NormalizationBudget.defaults(),
                "NONE", false));

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.truncationReasons().contains("ZERO_RETAINED_EVENTS"));
        assertTrue(result.summary().orElseThrow().methods().isEmpty());
    }

    @Test
    void boundsRetainedRelationshipsAndReportsPartialSummary() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"one","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":2,"threadName":"one","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":3,"eventType":"METHOD_ENTER","depth":1,"threadName":"two","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":4,"eventType":"METHOD_ENTER","depth":2,"threadName":"two","className":"fixture.Result","methodName":"commit","descriptor":"()V"}
                """);
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget budget = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), 1, defaults.maxHits(), defaults.maxFramesPerHit(),
                defaults.maxValueFacts(), defaults.maxScalarChars(), defaults.maxSummaryBytes());

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve", "choose", "commit")), budget,
                "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertEquals(1, result.summary().orElseThrow().observedPaths().size());
        assertTrue(result.truncationReasons().contains("RELATIONSHIP_BUDGET_EXCEEDED"));
        assertTrue(result.summary().orElseThrow().truncated());
    }

    @Test
    void marksEventIdOrderAnomalyPartialEvenWhenCallsAreBalanced() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":1,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                """);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve")), NormalizationBudget.defaults(),
                "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.truncationReasons().contains("EVENT_ID_ORDER_INVALID"));
        assertFalse(result.summary().orElseThrow().truncated());
    }

    @Test
    void stopsAddingFactsWhenSummaryOutputBudgetIsReached() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"thread-with-a-long-stable-name","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":2,"threadName":"thread-with-a-long-stable-name","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":3,"eventType":"METHOD_EXIT","depth":2,"threadName":"thread-with-a-long-stable-name","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":4,"eventType":"METHOD_EXIT","depth":1,"threadName":"thread-with-a-long-stable-name","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                """);
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget budget = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), 5_000);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve", "choose")), budget,
                "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.truncationReasons().contains("OUTPUT_BUDGET_EXCEEDED"));
        assertTrue(result.summary().orElseThrow().truncated());
    }

    @Test
    void rejectsMethodBudgetSmallerThanCollectionPlan() throws Exception {
        Path trace = write("");
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget budget = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                1, defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), defaults.maxSummaryBytes());

        assertThrows(IllegalArgumentException.class, () -> input(
                trace, plan(selectors("solve", "choose")), budget,
                "EXACT_DESCRIPTOR", false));
    }

    @Test
    void failsWhenIdentityCannotFitIntoSummaryBudget() throws Exception {
        Path trace = write("");
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget budget = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), defaults.maxHits(),
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), 1);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve")), budget, "NONE", false));

        assertEquals(NormalizationStatus.FAILED, result.status());
        assertTrue(result.summary().isEmpty());
        assertEquals("NORMALIZE_OUTPUT_BUDGET_TOO_SMALL", result.failureCode().orElseThrow());
    }

    @Test
    void returnsFailedResultForMalformedRawTrace() throws Exception {
        Path trace = write("not-json\n");

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve")), NormalizationBudget.defaults(),
                "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.FAILED, result.status());
        assertTrue(result.summary().isEmpty());
        assertEquals("NORMALIZE_JSON_INVALID", result.failureCode().orElseThrow());
        assertFalse(result.failureDetail().isBlank());
    }

    @Test
    void returnsFailedResultForIllegalEventDepth() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":-1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                """);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve")), NormalizationBudget.defaults(),
                "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.FAILED, result.status());
        assertEquals("NORMALIZE_SCHEMA_UNSUPPORTED", result.failureCode().orElseThrow());
        assertTrue(result.summary().isEmpty());
    }

    private MethodPathNormalizer normalizer() {
        return new MethodPathNormalizer();
    }

    private CodePathNormalizationInput input(
            Path trace,
            CodePathCollectionPlan plan,
            NormalizationBudget budget,
            String precision,
            boolean collectorTruncated) throws Exception {
        long bytes = Files.size(trace);
        return new CodePathNormalizationInput(
                new MethodPathCollectionRecord(
                        "1.0", CASE_ID, CONTEXT_ID, ANALYSIS_ID, RUN_ID, PLAN_ID,
                        COLLECTION_ID, TARGET, "CODEPATH", NOW),
                plan,
                new ArtifactReference(
                        "raw-1", "CODEPATH_FILTERED_TRACE",
                        "collections/collection-1/raw/filtered.jsonl",
                        "application/x-ndjson", HASH, bytes),
                trace, EVIDENCE_ID, budget, collectorTruncated, NOW);
    }

    private static CodePathCollectionPlan plan(List<MethodSelector> selectors) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, PLAN_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, TARGET, selectors,
                CollectionBudget.defaults(), "定位关键路径", NOW);
    }

    private static List<MethodSelector> selectors(String... methods) {
        return java.util.Arrays.stream(methods).map(method -> {
            String className = switch (method) {
                case "choose" -> "fixture.Decision";
                case "commit" -> "fixture.Result";
                default -> "fixture.Algorithm";
            };
            return new MethodSelector(
                    className + "#" + method + "()V", className, method, "()V");
        }).toList();
    }

    private Path write(String content) throws Exception {
        Path path = temporaryDirectory.resolve("trace-" + System.nanoTime() + ".jsonl");
        Files.writeString(path, content);
        return path;
    }
}
