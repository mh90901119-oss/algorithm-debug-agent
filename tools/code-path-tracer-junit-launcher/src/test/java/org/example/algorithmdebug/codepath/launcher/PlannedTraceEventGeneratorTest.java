package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takahirom.codepathtracer.AdviceData;
import io.github.takahirom.codepathtracer.TraceEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlannedTraceEventGeneratorTest {
    @Test
    void retainsOnlyExactDescriptorAndCarriesNoTargetObjectReference() {
        PlannedTraceEventGenerator generator = new PlannedTraceEventGenerator(plan(List.of()));
        Object targetObject = new Object();

        TraceEvent retained = generator.generate(new AdviceData.Enter(
                new Object[]{targetObject}, 7, Service.class, "solve", "()V"));
        TraceEvent wrongOverload = generator.generate(new AdviceData.Enter(
                new Object[0], 7, Service.class, "solve", "(I)V"));

        TraceEvent.Enter enter = assertInstanceOf(TraceEvent.Enter.class, retained);
        assertEquals(1, enter.getArgs().length);
        assertInstanceOf(CapturedProjectionValues.class, enter.getArgs()[0]);
        assertEquals(List.of(), generator.projections(enter));
        assertEquals("solve", generator.methodName(enter));
        assertEquals("()V", generator.descriptor(enter));
        assertEquals("METHOD_ENTER", generator.eventType(enter));
        assertNull(wrongOverload);
    }

    @Test
    void snapshotsNestedInheritedNullUnavailableAndTruncatedArgumentValues() {
        List<LauncherCodePathPlan.Projection> projections = List.of(
                projection("waferId", List.of("wafer", "id"), true),
                projection("inherited", List.of("inherited"), false),
                projection("missing", List.of("missing"), true),
                projection("complex", List.of("complex"), false),
                projection("longText", List.of("longText"), false));
        PlannedTraceEventGenerator generator = new PlannedTraceEventGenerator(plan(projections));
        Context context = new Context();

        TraceEvent event = generator.generate(new AdviceData.Enter(
                new Object[]{context}, 1, Service.class, "solve", "()V"));

        List<ProjectionValue> values = generator.projections(event);
        assertEquals(List.of(
                        ProjectionStatus.VALUE,
                        ProjectionStatus.VALUE,
                        ProjectionStatus.UNAVAILABLE,
                        ProjectionStatus.UNAVAILABLE,
                        ProjectionStatus.TRUNCATED),
                values.stream().map(ProjectionValue::status).toList());
        assertEquals("W-17", values.get(0).value());
        assertEquals("base", values.get(1).value());
        assertEquals("FIELD_NOT_FOUND", values.get(2).failureCode());
        assertEquals("NON_SCALAR_VALUE", values.get(3).failureCode());
        assertEquals(512, ((String) values.get(4).value()).length());
        context.wafer = null;
        TraceEvent nullEvent = generator.generate(new AdviceData.Enter(
                new Object[]{context}, 1, Service.class, "solve", "()V"));
        assertEquals(ProjectionStatus.NULL, generator.projections(nullEvent).get(0).status());
    }

    @Test
    void snapshotsReturnProjectionOnExit() {
        LauncherCodePathPlan.Projection returned = new LauncherCodePathPlan.Projection(
                "chamber", LauncherCodePathPlan.ProjectionSource.RETURN,
                null, List.of("chamber"), true);
        PlannedTraceEventGenerator generator = new PlannedTraceEventGenerator(plan(List.of(returned)));

        TraceEvent event = generator.generate(new AdviceData.Exit(
                new Result("PM-2"), 1, Service.class, "solve", "()V"));

        assertEquals("PM-2", generator.projections(event).get(0).value());
    }

    @Test
    void reportsSecondSelectedMethodThreadAndStopsRecording() throws Exception {
        PlannedTraceEventGenerator generator = new PlannedTraceEventGenerator(plan(List.of()));
        assertTrue(generator.matches(generator.generate(selectedEnter())));
        AtomicReference<TraceEvent> secondThreadEvent = new AtomicReference<>();
        Thread thread = new Thread(() -> secondThreadEvent.set(generator.generate(selectedEnter())));
        thread.start();
        thread.join();

        assertNull(secondThreadEvent.get());
        assertEquals("CODEPATH_MULTIPLE_THREADS_UNSUPPORTED", generator.failureCode());
        assertNull(generator.generate(selectedEnter()));
    }

    private static AdviceData.Enter selectedEnter() {
        return new AdviceData.Enter(new Object[0], 0, Service.class, "solve", "()V");
    }

    private static LauncherCodePathPlan.Projection projection(
            String name,
            List<String> fields,
            boolean required) {
        return new LauncherCodePathPlan.Projection(
                name, LauncherCodePathPlan.ProjectionSource.ARGUMENT,
                0, fields, required);
    }

    private static LauncherCodePathPlan plan(List<LauncherCodePathPlan.Projection> projections) {
        String className = Service.class.getName();
        var selector = new LauncherCodePathPlan.MethodSelector(
                className + "#solve()V", className, "solve", "()V");
        return new LauncherCodePathPlan(
                "4.0", "plan-1", "case-1", "analysis-1",
                new LauncherCodePathPlan.TargetTest("fixture.Test", "case1"),
                List.of(new LauncherCodePathPlan.MethodSelection(selector, projections)),
                selector.methodKey(), new LauncherCodePathPlan.Budget(100, 4096, 30_000),
                "fixture", new LauncherCodePathPlan.Intent(
                        "Which path ran?", "The selected method executes", List.of(), List.of()),
                Instant.EPOCH);
    }

    static class ParentContext {
        private final String inherited = "base";
    }

    static final class Context extends ParentContext {
        private Wafer wafer = new Wafer("W-17");
        private final Object complex = new Object();
        private final String longText = "x".repeat(600);
    }

    record Wafer(String id) {
    }

    record Result(String chamber) {
    }

    static final class Service {
        void solve() {}
        void solve(int value) {}
    }
}
