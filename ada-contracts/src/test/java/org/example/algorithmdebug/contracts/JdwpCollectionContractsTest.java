package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JdwpCollectionContractsTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void createsStackOnlyPlanWithDefensiveTracepointCopy() {
        var mutable = new java.util.ArrayList<>(List.of(tracepoint(
                "point-1", 11, 3, JdwpCaptureSpec.stackOnly())));

        JdwpCollectionPlan plan = plan(mutable, JdwpCollectionBudget.defaults());
        mutable.clear();

        assertEquals(1, plan.tracepoints().size());
        assertFalse(plan.tracepoints().getFirst().capture().locals());
        assertEquals(8, plan.tracepoints().getFirst().capture().maxFrames());
    }

    @Test
    void rejectsDuplicateTracepointIdsAndMoreThanTwentyPoints() {
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(
                tracepoint("same", 11, 1, JdwpCaptureSpec.stackOnly()),
                tracepoint("same", 12, 1, JdwpCaptureSpec.stackOnly())),
                JdwpCollectionBudget.defaults()));

        List<JdwpTracepointSpec> excessive = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(index -> tracepoint("point-" + index, 11, 1,
                        JdwpCaptureSpec.stackOnly()))
                .toList();
        assertThrows(IllegalArgumentException.class, () ->
                plan(excessive, JdwpCollectionBudget.defaults()));
    }

    @Test
    void rejectsLineOutsideSourceMethodAndMismatchedMethodIdentity() {
        SourceAnchor anchor = anchor();
        assertThrows(IllegalArgumentException.class, () -> new JdwpTracepointSpec(
                "point-1", methodKey(), anchor, 21, 1, JdwpCaptureSpec.stackOnly()));
        assertThrows(IllegalArgumentException.class, () -> new JdwpTracepointSpec(
                "point-1", "fixture.Algorithm#other()V", anchor, 11, 1,
                JdwpCaptureSpec.stackOnly()));
    }

    @Test
    void enforcesConservativeAllVisibleLocalsLimits() {
        assertThrows(IllegalArgumentException.class, () -> new JdwpCaptureSpec(
                true, true, 8, 3, 20, 256));
        assertThrows(IllegalArgumentException.class, () -> new JdwpCaptureSpec(
                true, true, 8, 1, 101, 256));
        assertThrows(IllegalArgumentException.class, () -> new JdwpCaptureSpec(
                false, false, 8, 1, 20, 256));

        JdwpCaptureSpec locals = new JdwpCaptureSpec(true, true, 8, 1, 20, 256);
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(
                tracepoint("point-1", 11, 6, locals)), JdwpCollectionBudget.defaults()));
    }

    @Test
    void enforcesJdwpSpecificProcessAndRawBudgets() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdwpCollectionBudget(1_001, 1, 1_000, 1_000));
        assertThrows(IllegalArgumentException.class, () ->
                new JdwpCollectionBudget(1, 50L * 1024 * 1024 + 1, 1_000, 1_000));
        assertThrows(IllegalArgumentException.class, () ->
                new JdwpCollectionBudget(1, 1, 20 * 60_000L + 1, 1_000));
        assertThrows(IllegalArgumentException.class, () ->
                new JdwpCollectionBudget(1, 1, 5_000, 6_000));
    }

    @Test
    void manifestDefensivelyCopiesCollectorCounters() {
        Map<String, Integer> hits = new LinkedHashMap<>();
        hits.put("point-1", 2);
        JdwpCollectionManifest manifest = new JdwpCollectionManifest(
                SchemaVersions.JDWP_COLLECTION_MANIFEST,
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "jdwp-batch-collector", "0.1.0-SNAPSHOT",
                JdwpCollectionCompletion.SUCCESS, "vm_death", JdwpCollectionStage.BASELINE_CHECKED,
                true, true, 0, 0, false, false, 4, 2_048,
                hits, Map.of("point-1", 1), Optional.empty(),
                "raw/jdwp.jsonl", "raw/collector-manifest.json",
                "logs/target-stdout.log", "logs/target-stderr.log",
                "logs/collector-stdout.log", "logs/collector-stderr.log", NOW, NOW);
        hits.clear();

        assertEquals(Map.of("point-1", 2), manifest.observedHitCounts());
        assertThrows(UnsupportedOperationException.class, () ->
                manifest.observedHitCounts().put("point-2", 1));
    }

    @Test
    void manifestRejectsContradictoryCompletionFacts() {
        assertThrows(IllegalArgumentException.class, () -> manifest(
                JdwpCollectionCompletion.TIMED_OUT, false, false, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> manifest(
                JdwpCollectionCompletion.TRUNCATED, false, false, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> manifest(
                JdwpCollectionCompletion.AGENT_FAILED, false, false, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> manifest(
                JdwpCollectionCompletion.SUCCESS, false, false, Optional.empty()));
    }

    @Test
    void requestIdentityIsFixedToJdwpCollector() {
        assertThrows(IllegalArgumentException.class, () -> new JdwpCollectionRecord(
                SchemaVersions.JDWP_COLLECTION_REQUEST,
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                new TargetTest("fixture.AlgorithmTest", "runs"), "CODEPATH", NOW));
    }

    private static JdwpCollectionPlan plan(
            List<JdwpTracepointSpec> tracepoints, JdwpCollectionBudget budget) {
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN,
                new PlanId("plan-1"), new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new TargetTest("fixture.AlgorithmTest", "runs"),
                tracepoints, budget, "采集关键决策位置", NOW);
    }

    private static JdwpTracepointSpec tracepoint(
            String id, int line, int maxHits, JdwpCaptureSpec capture) {
        return new JdwpTracepointSpec(id, methodKey(), anchor(), line, maxHits, capture);
    }

    private static SourceAnchor anchor() {
        return new SourceAnchor(
                "fixture.Algorithm", "schedule", "()V",
                "src/main/java/fixture/Algorithm.java", 10, 20);
    }

    private static String methodKey() {
        return "fixture.Algorithm#schedule()V";
    }

    private static JdwpCollectionManifest manifest(
            JdwpCollectionCompletion completion,
            boolean timedOut,
            boolean truncated,
            Optional<AgentFailureDiagnostic> failure) {
        return new JdwpCollectionManifest(
                SchemaVersions.JDWP_COLLECTION_MANIFEST,
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "jdwp-batch-collector", "1.0.0", completion, "test_completion",
                JdwpCollectionStage.FAILED,
                true, true, 1, 2, timedOut, truncated, 0, 0,
                Map.of(), Map.of(), failure,
                "raw/jdwp.jsonl", "raw/collector-manifest.json",
                "logs/target-stdout.log", "logs/target-stderr.log",
                "logs/collector-stdout.log", "logs/collector-stderr.log", NOW, NOW);
    }
}
