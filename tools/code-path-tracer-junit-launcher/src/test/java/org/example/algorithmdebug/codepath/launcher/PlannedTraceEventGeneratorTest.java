package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takahirom.codepathtracer.AdviceData;
import io.github.takahirom.codepathtracer.TraceEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlannedTraceEventGeneratorTest {
    @Test
    void retainsOnlyExactDescriptorAndDoesNotCarryArgumentsOrReturnValue() {
        PlannedTraceEventGenerator generator = new PlannedTraceEventGenerator(plan());

        TraceEvent retained = generator.generate(new AdviceData.Enter(
                new Object[]{"sensitive-large-object"}, 7, Service.class, "solve", "()V"));
        TraceEvent wrongOverload = generator.generate(new AdviceData.Enter(
                new Object[0], 7, Service.class, "solve", "(I)V"));

        TraceEvent.Enter enter = assertInstanceOf(TraceEvent.Enter.class, retained);
        assertEquals(0, enter.getArgs().length);
        assertEquals("solve", generator.methodName(enter));
        assertEquals("()V", generator.descriptor(enter));
        assertEquals("METHOD_ENTER", generator.eventType(enter));
        assertNull(wrongOverload);
    }

    @Test
    void reportsSecondSelectedMethodThreadAndStopsRecording() throws Exception {
        PlannedTraceEventGenerator generator = new PlannedTraceEventGenerator(plan());
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

    private static LauncherCodePathPlan plan() {
        String className = Service.class.getName();
        return LauncherCodePathPlan.fixture(
                new LauncherCodePathPlan.TargetTest("fixture.Test", "case1"),
                List.of(new LauncherCodePathPlan.MethodSelector(
                        className + "#solve()V", className, "solve", "()V")),
                new LauncherCodePathPlan.Budget(100, 4096, 30_000));
    }

    static final class Service {
        void solve() {}
        void solve(int value) {}
    }
}
