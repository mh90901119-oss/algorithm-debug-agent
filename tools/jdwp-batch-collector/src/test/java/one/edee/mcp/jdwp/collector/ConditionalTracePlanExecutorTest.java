package one.edee.mcp.jdwp.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConditionalTracePlanExecutorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void separatesObservedMatchedCapturedAndUnavailableCounts() throws Exception {
        DebugPlan plan = DebugPlanTest.validPlan();
        DebugPlan.Tracepoint point = plan.tracepoints.getFirst();
        point.maxObservedHits = 5;
        point.maxCapturedHits = 1;
        point.captureFirstMatchedHits = 1;
        point.captureEveryMatchedHits = 0;
        point.capture.stack = false;
        point.capture.valuePaths = List.of("waferNumber");
        DebugPlan.Condition condition = new DebugPlan.Condition();
        condition.valuePath = "waferNumber";
        condition.expectedType = "LONG";
        condition.expectedValue = "2";
        point.conditions.add(condition);
        plan.validate();

        VirtualMachine vm = mock(VirtualMachine.class);
        EventRequestManager manager = mock(EventRequestManager.class);
        EventQueue queue = mock(EventQueue.class);
        ReferenceType type = mock(ReferenceType.class);
        Location location = location();
        BreakpointRequest request = mock(BreakpointRequest.class);
        ThreadReference thread = mock(ThreadReference.class);
        StackFrame frame = mock(StackFrame.class);
        LocalVariable variable = mock(LocalVariable.class);
        IntegerValue firstValue = mock(IntegerValue.class);
        IntegerValue secondValue = mock(IntegerValue.class);
        Type integerType = mock(Type.class);
        BreakpointEvent first = breakpoint(request, thread, location);
        BreakpointEvent second = breakpoint(request, thread, location);
        EventSet events = mock(EventSet.class);

        when(vm.eventRequestManager()).thenReturn(manager);
        when(vm.eventQueue()).thenReturn(queue);
        when(vm.name()).thenReturn("fixture-vm");
        when(vm.classesByName("fixture.Algorithm")).thenReturn(List.of(type));
        when(type.name()).thenReturn("fixture.Algorithm");
        when(type.locationsOfLine(12)).thenReturn(List.of(location));
        when(manager.createBreakpointRequest(location)).thenReturn(request);
        when(request.getProperty("collector.tracepoint")).thenReturn("point-1");
        when(thread.frame(0)).thenReturn(frame);
        when(frame.visibleVariableByName("waferNumber")).thenReturn(variable);
        when(frame.getValue(variable)).thenReturn(firstValue, secondValue);
        when(firstValue.longValue()).thenReturn(1L);
        when(secondValue.longValue()).thenReturn(2L);
        when(firstValue.type()).thenReturn(integerType);
        when(secondValue.type()).thenReturn(integerType);
        when(integerType.name()).thenReturn("int");
        when(thread.uniqueID()).thenReturn(7L);
        when(thread.name()).thenReturn("main");
        when(events.iterator()).thenReturn(List.<Event>of(first, second).iterator());
        when(queue.remove(anyLong())).thenReturn(events).thenThrow(new VMDisconnectedException());

        TracePlanExecutor.CollectionResult result;
        try (JsonlTraceWriter writer = new JsonlTraceWriter(
                new ObjectMapper(), temporaryDirectory.resolve("trace.jsonl"))) {
            result = new TracePlanExecutor(vm, plan, writer).execute();
        }

        assertEquals(2, result.observedHitCounts().get("point-1"));
        assertEquals(1, result.matchedHitCounts().get("point-1"));
        assertEquals(1, result.capturedHitCounts().get("point-1"));
        assertEquals(0, result.conditionUnavailableCounts().getOrDefault("point-1", 0));
    }

    private static BreakpointEvent breakpoint(
            BreakpointRequest request, ThreadReference thread, Location location) {
        BreakpointEvent event = mock(BreakpointEvent.class);
        when(event.request()).thenReturn(request);
        when(event.thread()).thenReturn(thread);
        when(event.location()).thenReturn(location);
        return event;
    }

    private static Location location() {
        Location location = mock(Location.class);
        Method method = mock(Method.class);
        ReferenceType type = mock(ReferenceType.class);
        when(location.method()).thenReturn(method);
        when(location.declaringType()).thenReturn(type);
        when(location.lineNumber()).thenReturn(12);
        when(location.codeIndex()).thenReturn(7L);
        when(method.name()).thenReturn("solve");
        when(method.signature()).thenReturn("()V");
        when(type.classLoader()).thenReturn(null);
        when(type.name()).thenReturn("fixture.Algorithm");
        return location;
    }
}
