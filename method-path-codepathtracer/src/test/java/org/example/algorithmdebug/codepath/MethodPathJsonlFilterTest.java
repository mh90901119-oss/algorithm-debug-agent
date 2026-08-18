package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodPathJsonlFilterTest {

    @TempDir
    Path directory;

    @Test
    void streamsOnlySelectedEnterAndExitEvents() throws Exception {
        Path raw = Files.writeString(directory.resolve("raw.jsonl"), """
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Target","methodName":"run"}
                {"eventId":2,"eventType":"METHOD_ENTER","depth":2,"threadName":"main","className":"fixture.Service","methodName":"solve"}
                {"eventId":3,"eventType":"METHOD_EXIT","depth":2,"threadName":"main","className":"fixture.Service","methodName":"solve"}
                {"eventId":4,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Target","methodName":"run"}
                """);
        Path filtered = directory.resolve("filtered.jsonl");

        MethodPathFilterResult result = new MethodPathJsonlFilter().filter(raw, filtered, plan(
                new CollectionBudget(10, 10_000, 1_000, 10)));

        assertEquals(4, result.rawEventCount());
        assertEquals(2, result.retainedEventCount());
        assertFalse(result.truncated());
        assertEquals(2, Files.readAllLines(filtered).size());
        assertTrue(Files.readString(filtered).contains("METHOD_EXIT"));
    }

    @Test
    void truncatesDeterministicallyAtRetainedEventBudget() throws Exception {
        Path raw = Files.writeString(directory.resolve("raw.jsonl"), """
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"worker-1","className":"fixture.Service","methodName":"solve"}
                {"eventId":2,"eventType":"METHOD_EXIT","depth":1,"threadName":"worker-1","className":"fixture.Service","methodName":"solve"}
                """);

        MethodPathFilterResult result = new MethodPathJsonlFilter().filter(
                raw, directory.resolve("filtered.jsonl"),
                plan(new CollectionBudget(1, 10_000, 1_000, 10)));

        assertTrue(result.truncated());
        assertEquals(1, result.retainedEventCount());
        assertTrue(result.truncationReason().orElseThrow().contains("maxEvents"));
    }

    @Test
    void rejectsInvalidJsonlWithoutPublishingFinalFile() throws Exception {
        Path raw = Files.writeString(directory.resolve("raw.jsonl"), "{broken\n");
        Path filtered = directory.resolve("filtered.jsonl");

        assertThrows(CodePathAdapterException.class,
                () -> new MethodPathJsonlFilter().filter(raw, filtered, plan(CollectionBudget.defaults())));
        assertFalse(Files.exists(filtered));
    }

    @Test
    void countsPortableLfExactlyAgainstByteBudget() throws Exception {
        String line = "{\"eventId\":1,\"eventType\":\"METHOD_ENTER\",\"depth\":1,\"threadName\":\"main\",\"className\":\"fixture.Service\",\"methodName\":\"solve\"}";
        Path raw = Files.writeString(directory.resolve("raw.jsonl"), line + "\n");
        long canonicalBytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;

        MethodPathFilterResult result = new MethodPathJsonlFilter().filter(
                raw, directory.resolve("filtered.jsonl"),
                plan(new CollectionBudget(10, canonicalBytes, 1_000, 10)));

        assertFalse(result.truncated());
        assertEquals(canonicalBytes, result.filteredBytes());
    }

    @Test
    void usesDescriptorWhenPresentAndDisclosesMissingDescriptorDegradation() throws Exception {
        Path raw = Files.writeString(directory.resolve("raw-descriptor.jsonl"), """
                {"eventId":1,"eventType":"METHOD_ENTER","depth":1,"threadName":"main","className":"fixture.Service","methodName":"solve","descriptor":"(I)I"}
                {"eventId":2,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Service","methodName":"solve","descriptor":"()V"}
                {"eventId":3,"eventType":"METHOD_EXIT","depth":1,"threadName":"main","className":"fixture.Service","methodName":"solve"}
                """);

        MethodPathFilterResult result = new MethodPathJsonlFilter().filter(
                raw, directory.resolve("filtered-descriptor.jsonl"),
                plan(CollectionBudget.defaults()));

        assertEquals(2, result.retainedEventCount());
        assertEquals(1, result.exactDescriptorMatchCount());
        assertEquals(1, result.degradedClassMethodMatchCount());
        assertEquals(2, Files.readAllLines(directory.resolve("filtered-descriptor.jsonl")).size());
    }

    @Test
    void rejectsOversizedRawBeforeCreatingDerivedTrace() throws Exception {
        Path raw = Files.writeString(directory.resolve("raw-breach.jsonl"), "x".repeat(20_000));
        Path filtered = directory.resolve("filtered-breach.jsonl");

        CodePathAdapterException failure = assertThrows(CodePathAdapterException.class,
                () -> new MethodPathJsonlFilter().filter(raw, filtered,
                        plan(new CollectionBudget(10, 10_000, 1_000, 10))));

        assertEquals("CODEPATH_RAW_LIMIT_BREACH", failure.code());
        assertFalse(Files.exists(filtered));
    }

    @Test
    void rejectsGiantLineWithoutPublishingDerivedTrace() throws Exception {
        Path raw = Files.writeString(directory.resolve("giant.jsonl"),
                "{" + "x".repeat(1_048_576) + "}");
        Path filtered = directory.resolve("filtered-giant.jsonl");

        CodePathAdapterException failure = assertThrows(CodePathAdapterException.class,
                () -> new MethodPathJsonlFilter().filter(raw, filtered,
                        plan(new CollectionBudget(10, 2L * 1024 * 1024, 1_000, 10))));

        assertEquals("CODEPATH_TRACE_LINE_TOO_LARGE", failure.code());
        assertFalse(Files.exists(filtered));
    }

    private CodePathCollectionPlan plan(CollectionBudget budget) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.TargetTest", "case1"), "a".repeat(64),
                List.of(new MethodSelector("fixture.Service#solve(I)I", "fixture.Service",
                        "solve", "(I)I", "b".repeat(64))),
                List.of("fixture"), "PACKAGE_SUPERSET", budget, 100, "定位", Instant.EPOCH);
    }
}
