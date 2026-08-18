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
        Thread thread = Thread.ofPlatform().start(() -> secondThreadEvent.set(generator.generate(selectedEnter())));
        thread.join();

        assertNull(secondThreadEvent.get());
        assertEquals("CODEPATH_MULTIPLE_THREADS_UNSUPPORTED", generator.failureCode());
        assertNull(generator.generate(selectedEnter()));
    }

    private static AdviceData.Enter selectedEnter() {
        return new AdviceData.Enter(new Object[0], 0, Service.class, "solve", "()V");
    }

    private static CodePathCollectionPlan plan() {
        String className = Service.class.getName();
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"),
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.Test", "case1"),
                List.of(new MethodSelector(className + "#solve()V", className, "solve", "()V")),
                CollectionBudget.defaults(), "测试精确方法选择", Instant.EPOCH);
    }

    static final class Service {
        void solve() {}
        void solve(int value) {}
    }
}
