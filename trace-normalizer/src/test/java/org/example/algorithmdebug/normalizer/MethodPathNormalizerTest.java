package org.example.algorithmdebug.normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
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
    void groupsScopeInvocationsAndIdentifiesAnOutlierPath() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":2,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":3,"eventType":"METHOD_EXIT","depth":2,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":4,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":5,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":6,"eventType":"METHOD_ENTER","depth":2,"threadName":"main","className":"fixture.Result","methodName":"commit","descriptor":"()V"}
                {"eventId":7,"eventType":"METHOD_EXIT","depth":2,"threadName":"main","className":"fixture.Result","methodName":"commit","descriptor":"()V"}
                {"eventId":8,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":9,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":10,"eventType":"METHOD_ENTER","depth":2,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":11,"eventType":"METHOD_EXIT","depth":2,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":12,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                """);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve", "choose", "commit"),
                        Optional.of("fixture.Algorithm#solve()V")),
                NormalizationBudget.defaults(), "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.COMPLETE, result.status());
        MethodPathSummary.ScopeSummary scope = result.summary().orElseThrow().scope().orElseThrow();
        assertEquals(3, scope.invocationCount());
        assertEquals(3, scope.completeInvocationCount());
        assertEquals(0, scope.incompleteInvocationCount());
        assertEquals(2, scope.pathVariants().size());
        assertEquals(List.of(1, 3), scope.pathVariants().stream()
                .filter(variant -> variant.representativeMethodSequence().contains(
                        "fixture.Decision#choose()V"))
                .findFirst().orElseThrow().representativeInvocationOrdinals());
        assertEquals(List.of(2), scope.pathVariants().stream()
                .filter(variant -> variant.representativeMethodSequence().contains(
                        "fixture.Result#commit()V"))
                .findFirst().orElseThrow().representativeInvocationOrdinals());
    }

    @Test
    void marksOpenScopeInvocationPartialAtEndOfTrace() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":2,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                {"eventId":3,"eventType":"METHOD_EXIT","depth":2,"threadName":"main","className":"fixture.Decision","methodName":"choose","descriptor":"()V"}
                """);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve", "choose"),
                        Optional.of("fixture.Algorithm#solve()V")),
                NormalizationBudget.defaults(), "EXACT_DESCRIPTOR", false));

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        MethodPathSummary.ScopeSummary scope = result.summary().orElseThrow().scope().orElseThrow();
        assertEquals(1, scope.invocationCount());
        assertEquals(0, scope.completeInvocationCount());
        assertEquals(1, scope.incompleteInvocationCount());
        assertTrue(scope.invocations().getFirst().endEventId().isEmpty());
        assertTrue(scope.invocations().getFirst().pathId().isEmpty());
    }

    @Test
    void countsAllScopeInvocationsWhileBoundingRepresentativeDetails() throws Exception {
        StringBuilder raw = new StringBuilder();
        long eventId = 1;
        for (int invocation = 0; invocation < 5; invocation++) {
            raw.append("{\"eventId\":").append(eventId++)
                    .append(",\"eventType\":\"METHOD_ENTER\",\"depth\":1,\"threadName\":\"main\",\"className\":\"fixture.Algorithm\",\"methodName\":\"solve\",\"descriptor\":\"()V\"}\n");
            raw.append("{\"eventId\":").append(eventId++)
                    .append(",\"eventType\":\"METHOD_EXIT\",\"depth\":1,\"threadName\":\"main\",\"className\":\"fixture.Algorithm\",\"methodName\":\"solve\",\"descriptor\":\"()V\"}\n");
        }
        Path trace = write(raw.toString());
        NormalizationBudget defaults = NormalizationBudget.defaults();
        NormalizationBudget budget = new NormalizationBudget(
                defaults.maxRawBytes(), defaults.maxRecordBytes(), defaults.maxRecords(),
                defaults.maxMethods(), defaults.maxRelationships(), 2,
                defaults.maxFramesPerHit(), defaults.maxValueFacts(),
                defaults.maxScalarChars(), defaults.maxSummaryBytes());

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input(
                trace, plan(selectors("solve"), Optional.of("fixture.Algorithm#solve()V")),
                budget, "EXACT_DESCRIPTOR", false));

        MethodPathSummary.ScopeSummary scope = result.summary().orElseThrow().scope().orElseThrow();
        assertEquals(5, scope.invocationCount());
        assertEquals(5, scope.completeInvocationCount());
        assertEquals(2, scope.invocations().size());
        assertEquals(5, scope.pathVariants().getFirst().occurrenceCount());
        assertEquals(List.of(1, 2),
                scope.pathVariants().getFirst().representativeInvocationOrdinals());
        assertTrue(result.truncationReasons().contains("SCOPE_REPRESENTATIVE_BUDGET_EXCEEDED"));
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

    @Test
    void derivesPairedInvocationProjectionRowsAndMarksRequiredReadFailuresPartial() throws Exception {
        Path trace = write("""
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V","projections":[{"name":"waferId","path":"arg[0].waferId","required":true,"status":"VALUE","value":"W-1","failureCode":null}]}
                {"eventId":2,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Algorithm","methodName":"solve","descriptor":"()V","projections":[{"name":"chamber","path":"return.chamber","required":true,"status":"UNAVAILABLE","value":null,"failureCode":"FIELD_NOT_FOUND"}]}
                """);
        Path invocations = temporaryDirectory.resolve("derived-invocations.jsonl");
        CodePathNormalizationInput input = input(
                trace, plan(selectors("solve")), NormalizationBudget.defaults(),
                "EXACT_DESCRIPTOR", false, invocations);

        NormalizationResult<MethodPathSummary> result = normalizer().normalize(input);

        assertEquals(NormalizationStatus.PARTIAL, result.status());
        assertTrue(result.truncationReasons().contains("REQUIRED_PROJECTION_UNAVAILABLE"));
        com.fasterxml.jackson.databind.JsonNode invocation = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readString(invocations).strip());
        assertEquals("fixture.Algorithm#solve()V", invocation.path("methodRef").asText());
        assertEquals("W-1", invocation.path("projections").get(0).path("value").asText());
        assertEquals("FIELD_NOT_FOUND",
                invocation.path("projections").get(1).path("failureCode").asText());
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
        return input(trace, plan, budget, precision, collectorTruncated,
                temporaryDirectory.resolve("invocations-" + System.nanoTime() + ".jsonl"));
    }

    private CodePathNormalizationInput input(
            Path trace,
            CodePathCollectionPlan plan,
            NormalizationBudget budget,
            String precision,
            boolean collectorTruncated,
            Path invocationOutput) throws Exception {
        long bytes = Files.size(trace);
        return new CodePathNormalizationInput(
                new MethodPathCollectionRecord(
                        "1.0", CASE_ID, ANALYSIS_ID, RUN_ID, PLAN_ID,
                        COLLECTION_ID, TARGET, "CODEPATH", NOW),
                plan,
                new ArtifactReference(
                        "raw-1", "CODEPATH_FILTERED_TRACE",
                        "collections/collection-1/raw/filtered.jsonl",
                        "application/x-ndjson", HASH, bytes),
                trace, invocationOutput, EVIDENCE_ID, budget, collectorTruncated, NOW);
    }

    private static CodePathCollectionPlan plan(
            List<MethodSelector> selectors, Optional<String> scopeMethodKey) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, PLAN_ID, CASE_ID, ANALYSIS_ID, TARGET,
                methodSelections(selectors), scopeMethodKey,
                CollectionBudget.defaults(), "locate repeated paths", intent(), NOW);
    }

    private static CodePathCollectionPlan plan(List<MethodSelector> selectors) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, PLAN_ID, CASE_ID, ANALYSIS_ID, TARGET,
                methodSelections(selectors), Optional.empty(),
                CollectionBudget.defaults(), "Locate the key path", intent(), NOW);
    }

    private static List<org.example.algorithmdebug.contracts.CodePathMethodSelection> methodSelections(
            List<MethodSelector> selectors) {
        return selectors.stream().map(selector ->
                new org.example.algorithmdebug.contracts.CodePathMethodSelection(selector, List.of()))
                .toList();
    }

    private static org.example.algorithmdebug.contracts.InvestigationIntent intent() {
        return new org.example.algorithmdebug.contracts.InvestigationIntent(
                "Which path executed?", "The selected path executed", List.of(),
                List.of("Observed method path"));
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
        String normalized = content.lines()
                .map(line -> line.contains("\"projections\"")
                        ? line
                        : line.replaceFirst("}$", ",\"projections\":[]}"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        Files.writeString(path, normalized);
        return path;
    }
}
