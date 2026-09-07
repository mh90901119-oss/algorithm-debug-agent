package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePathCaptureRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCOPE = Scope.class.getName() + "#run(Ljava/lang/Object;)V";
    private static final String CHILD = Child.class.getName() + "#step()V";
    @TempDir Path temporaryDirectory;

    @AfterEach
    void resetRuntime() {
        CodePathCaptureRuntime.deactivate();
    }

    @Test
    void retainsOnlyTheRequestedMatchedScopeOrdinalWindow() throws Exception {
        Path raw = temporaryDirectory.resolve("codepath.jsonl");
        CodePathCaptureRuntime runtime = CodePathCaptureRuntime.activate(
                plan(LauncherCodePathPlan.CaptureMode.TRACE, 2, 2), raw);

        for (int ordinal = 1; ordinal <= 4; ordinal++) {
            long scope = CodePathCaptureRuntime.enter(
                    Scope.class.getName(), "run", "(Ljava/lang/Object;)V",
                    new Object[]{new Entity("E-17")});
            long child = CodePathCaptureRuntime.enter(
                    Child.class.getName(), "step", "()V", new Object[0]);
            CodePathCaptureRuntime.exit(
                    Child.class.getName(), "step", "()V", null, null, child);
            CodePathCaptureRuntime.exit(
                    Scope.class.getName(), "run", "(Ljava/lang/Object;)V", null, null, scope);
        }
        runtime.close();

        List<String> lines = Files.readAllLines(raw);
        assertEquals(8, lines.size());
        assertEquals(4, runtime.result().scopeInvocationsObserved());
        assertEquals(4, runtime.result().scopeInvocationsMatched());
        assertEquals(2, runtime.result().scopeInvocationsCaptured());
        assertEquals(2, runtime.result().scopeInvocationsSkippedByWindow());
    }

    @Test
    void evaluatesOnlyConditionProjectionsForANonMatchingScope() throws Exception {
        CodePathCaptureRuntime runtime = CodePathCaptureRuntime.activate(
                plan(LauncherCodePathPlan.CaptureMode.TRACE, 1, 10),
                temporaryDirectory.resolve("filtered.jsonl"));

        long token = CodePathCaptureRuntime.enter(
                Scope.class.getName(), "run", "(Ljava/lang/Object;)V",
                new Object[]{new Entity("OTHER")});
        CodePathCaptureRuntime.exit(
                Scope.class.getName(), "run", "(Ljava/lang/Object;)V", null, null, token);
        runtime.close();

        assertEquals(1, runtime.result().conditionProjectionReads());
        assertEquals(0, runtime.result().detailProjectionReads());
        assertEquals(0, runtime.result().eventsWritten());
    }

    @Test
    void aggregatesOneHundredThousandCallsIntoOneBoundedDocument() throws Exception {
        Path raw = temporaryDirectory.resolve("codepath-aggregate.json");
        CodePathCaptureRuntime runtime = CodePathCaptureRuntime.activate(
                unscopedAggregatePlan(), raw);

        for (int index = 0; index < 100_000; index++) {
            long token = CodePathCaptureRuntime.enter(
                    Child.class.getName(), "step", "()V", new Object[0]);
            CodePathCaptureRuntime.exit(
                    Child.class.getName(), "step", "()V", null, null, token);
        }
        runtime.close();

        JsonNode aggregate = JSON.readTree(raw.toFile());
        assertEquals("AGGREGATE", aggregate.path("captureMode").asText());
        assertEquals(100_000, aggregate.path("methods").get(0).path("enterCount").asLong());
        assertEquals(100_000, aggregate.path("methods").get(0).path("normalExitCount").asLong());
        assertTrue(Files.size(raw) < 65_536);
    }

    @Test
    void exceptionalExitClosesTheSelectedCallStack() throws Exception {
        Path raw = temporaryDirectory.resolve("exceptions.json");
        CodePathCaptureRuntime runtime = CodePathCaptureRuntime.activate(unscopedAggregatePlan(), raw);

        long failed = CodePathCaptureRuntime.enter(
                Child.class.getName(), "step", "()V", new Object[0]);
        CodePathCaptureRuntime.exit(
                Child.class.getName(), "step", "()V", null,
                new IllegalStateException("expected"), failed);
        long next = CodePathCaptureRuntime.enter(
                Child.class.getName(), "step", "()V", new Object[0]);
        CodePathCaptureRuntime.exit(
                Child.class.getName(), "step", "()V", null, null, next);
        runtime.close();

        JsonNode method = JSON.readTree(raw.toFile()).path("methods").get(0);
        assertEquals(1, method.path("exceptionalExitCount").asLong());
        assertEquals(0, method.path("minSelectedDepth").asInt());
        assertEquals(0, method.path("maxSelectedDepth").asInt());
    }

    @Test
    void aggregateKeepsNearestSelectedParentAfterRecursionReturns() throws Exception {
        String recursive = Recursive.class.getName() + "#run()V";
        String child = Child.class.getName() + "#step()V";
        LauncherCodePathPlan plan = aggregatePlan(recursive, child);
        Path raw = temporaryDirectory.resolve("recursive.json");
        CodePathCaptureRuntime runtime = CodePathCaptureRuntime.activate(plan, raw);

        long outer = CodePathCaptureRuntime.enter(Recursive.class.getName(), "run", "()V", null);
        long inner = CodePathCaptureRuntime.enter(Recursive.class.getName(), "run", "()V", null);
        CodePathCaptureRuntime.exit(Recursive.class.getName(), "run", "()V", null, null, inner);
        long nestedChild = CodePathCaptureRuntime.enter(Child.class.getName(), "step", "()V", null);
        CodePathCaptureRuntime.exit(Child.class.getName(), "step", "()V", null, null, nestedChild);
        CodePathCaptureRuntime.exit(Recursive.class.getName(), "run", "()V", null, null, outer);
        runtime.close();

        String paths = JSON.readTree(raw.toFile()).path("observedPaths").toString();
        assertTrue(paths.contains(recursive));
        assertTrue(paths.contains(child));
    }

    private static LauncherCodePathPlan plan(
            LauncherCodePathPlan.CaptureMode mode, int start, int maximum) {
        var scopeSelector = new LauncherCodePathPlan.MethodSelector(
                SCOPE, Scope.class.getName(), "run", "(Ljava/lang/Object;)V");
        var childSelector = new LauncherCodePathPlan.MethodSelector(
                CHILD, Child.class.getName(), "step", "()V");
        return new LauncherCodePathPlan(
                "6.0", "plan-1", "case-1", "analysis-1",
                new LauncherCodePathPlan.TargetTest("fixture.Test", "case1"),
                List.of(
                        new LauncherCodePathPlan.MethodSelection(scopeSelector, List.of(
                                new LauncherCodePathPlan.Projection(
                                        "entityId", LauncherCodePathPlan.ProjectionSource.ARGUMENT,
                                        0, List.of("id"), true),
                                new LauncherCodePathPlan.Projection(
                                        "unusedDetail", LauncherCodePathPlan.ProjectionSource.ARGUMENT,
                                        0, List.of("missing"), false))),
                        new LauncherCodePathPlan.MethodSelection(childSelector, List.of())),
                SCOPE, List.of(new LauncherCodePathPlan.ScopeCondition(
                        "entityId", LauncherCodePathPlan.ScalarType.STRING, "E-17")),
                mode, start, maximum,
                new LauncherCodePathPlan.Budget(1_000_000, 16_777_216, 30_000),
                "fixture", new LauncherCodePathPlan.Intent(
                        "Which path ran?", "The selected method executes", List.of(), List.of("path")),
                Instant.EPOCH);
    }

    private static LauncherCodePathPlan unscopedAggregatePlan() {
        var selector = new LauncherCodePathPlan.MethodSelector(
                CHILD, Child.class.getName(), "step", "()V");
        return new LauncherCodePathPlan(
                "6.0", "plan-1", "case-1", "analysis-1",
                new LauncherCodePathPlan.TargetTest("fixture.Test", "case1"),
                List.of(new LauncherCodePathPlan.MethodSelection(selector, List.of())),
                null, List.of(), LauncherCodePathPlan.CaptureMode.AGGREGATE, 1, 10_000,
                new LauncherCodePathPlan.Budget(1_000_000, 16_777_216, 30_000),
                "fixture", new LauncherCodePathPlan.Intent(
                        "Which path ran?", "The selected method executes", List.of(), List.of("path")),
                Instant.EPOCH);
    }

    private static LauncherCodePathPlan aggregatePlan(String... methodKeys) {
        List<LauncherCodePathPlan.MethodSelection> selections = java.util.Arrays.stream(methodKeys)
                .map(MethodIdentity::parse)
                .map(identity -> new LauncherCodePathPlan.MethodSelection(
                        new LauncherCodePathPlan.MethodSelector(identity.methodKey(), identity.className(),
                                identity.methodName(), identity.descriptor()), List.of()))
                .toList();
        return new LauncherCodePathPlan(
                "6.0", "plan-recursive", "case-1", "analysis-1",
                new LauncherCodePathPlan.TargetTest("fixture.Test", "case1"),
                selections, null, List.of(), LauncherCodePathPlan.CaptureMode.AGGREGATE, 1, 10_000,
                new LauncherCodePathPlan.Budget(1_000_000, 16_777_216, 30_000),
                "fixture", new LauncherCodePathPlan.Intent(
                        "Which path ran?", "selected methods execute", List.of(), List.of("path")),
                Instant.EPOCH);
    }

    record Entity(String id) {}
    static final class Scope { void run(Object value) {} }
    static final class Child { void step() {} }
    static final class Recursive { void run() {} }
}
