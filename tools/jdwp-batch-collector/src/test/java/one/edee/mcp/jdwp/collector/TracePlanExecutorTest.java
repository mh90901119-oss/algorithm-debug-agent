package one.edee.mcp.jdwp.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracePlanExecutorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsOnePrepareRequestForMultipleTracepointsInTheSameUnloadedClass() throws Exception {
        DebugPlan plan = DebugPlanTest.validPlan();
        DebugPlan.Tracepoint second = new DebugPlan.Tracepoint();
        second.id = "point-2";
        second.className = "fixture.Algorithm";
        second.methodName = "solve";
        second.methodDescriptor = "(I)V";
        second.line = 13;
        plan.tracepoints.add(second);
        plan.validate();

        VirtualMachine vm = disconnectedVm();
        EventRequestManager manager = vm.eventRequestManager();
        when(vm.classesByName("fixture.Algorithm")).thenReturn(List.of());
        when(manager.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));

        execute(vm, plan);

        verify(manager, times(1)).createClassPrepareRequest();
        verify(vm, times(1)).classesByName("fixture.Algorithm");
    }

    @Test
    void installsOnlyTheLocationWithThePlannedMethodDescriptor() throws Exception {
        DebugPlan plan = DebugPlanTest.validPlan();
        plan.validate();
        VirtualMachine vm = disconnectedVm();
        EventRequestManager manager = vm.eventRequestManager();
        ReferenceType type = mock(ReferenceType.class);
        Location matching = location("solve", "()V", 12, 7);
        Location overload = location("solve", "(I)V", 12, 9);
        BreakpointRequest request = mock(BreakpointRequest.class);
        when(type.name()).thenReturn("fixture.Algorithm");
        when(vm.classesByName("fixture.Algorithm")).thenReturn(List.of(type));
        when(type.locationsOfLine(12)).thenReturn(List.of(matching, overload));
        when(manager.createBreakpointRequest(matching)).thenReturn(request);

        execute(vm, plan);

        verify(manager).createBreakpointRequest(matching);
        verify(manager, never()).createBreakpointRequest(overload);
        assertEquals(1, timesCalledInstalled(request));
    }

    @Test
    void expandsAndWritesOnlyHitsSelectedByTheSamplingPolicy() throws Exception {
        DebugPlan plan = DebugPlanTest.validPlan();
        DebugPlan.Tracepoint point = plan.tracepoints.getFirst();
        point.maxObservedHits = 2;
        point.maxCapturedHits = 1;
        point.captureFirstMatchedHits = 0;
        point.captureEveryMatchedHits = 2;
        point.capture.locals = false;
        point.capture.stack = false;
        plan.validate();

        VirtualMachine vm = mock(VirtualMachine.class);
        EventRequestManager manager = mock(EventRequestManager.class);
        EventQueue queue = mock(EventQueue.class);
        ReferenceType type = mock(ReferenceType.class);
        Location location = location("solve", "()V", 12, 7);
        BreakpointRequest request = mock(BreakpointRequest.class);
        BreakpointEvent first = mock(BreakpointEvent.class);
        BreakpointEvent second = mock(BreakpointEvent.class);
        ThreadReference thread = mock(ThreadReference.class);
        EventSet events = mock(EventSet.class);
        when(vm.eventRequestManager()).thenReturn(manager);
        when(vm.eventQueue()).thenReturn(queue);
        when(vm.name()).thenReturn("fixture-vm");
        when(vm.classesByName("fixture.Algorithm")).thenReturn(List.of(type));
        when(type.name()).thenReturn("fixture.Algorithm");
        when(type.locationsOfLine(12)).thenReturn(List.of(location));
        when(manager.createBreakpointRequest(location)).thenReturn(request);
        when(request.getProperty("collector.tracepoint")).thenReturn("point-1");
        when(first.request()).thenReturn(request);
        when(second.request()).thenReturn(request);
        when(second.thread()).thenReturn(thread);
        when(second.location()).thenReturn(location);
        when(thread.uniqueID()).thenReturn(7L);
        when(thread.name()).thenReturn("main");
        when(events.iterator()).thenReturn(List.<Event>of(first, second).iterator());
        when(queue.remove(anyLong())).thenReturn(events).thenThrow(new VMDisconnectedException());

        Path trace = execute(vm, plan);
        List<JsonNode> hits = Files.readAllLines(trace).stream()
                .map(line -> {
                    try {
                        return new ObjectMapper().readTree(line);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .filter(json -> "tracepoint_hit".equals(json.path("eventType").asText()))
                .toList();

        assertEquals(1, hits.size());
        assertEquals(2, hits.getFirst().path("hit").asInt());
        verify(first, never()).thread();
    }

    private Path execute(VirtualMachine vm, DebugPlan plan) throws Exception {
        Path trace = temporaryDirectory.resolve("trace-" + System.nanoTime() + ".jsonl");
        try (JsonlTraceWriter writer = new JsonlTraceWriter(new ObjectMapper(), trace)) {
            new TracePlanExecutor(vm, plan, writer).execute();
        }
        return trace;
    }

    private static VirtualMachine disconnectedVm() throws Exception {
        VirtualMachine vm = mock(VirtualMachine.class);
        EventRequestManager manager = mock(EventRequestManager.class);
        EventQueue queue = mock(EventQueue.class);
        when(vm.eventRequestManager()).thenReturn(manager);
        when(vm.eventQueue()).thenReturn(queue);
        when(vm.name()).thenReturn("fixture-vm");
        when(queue.remove(anyLong())).thenThrow(new VMDisconnectedException());
        return vm;
    }

    private static Location location(String name, String descriptor, int line, long codeIndex) {
        Location location = mock(Location.class);
        Method method = mock(Method.class);
        ReferenceType type = mock(ReferenceType.class);
        when(location.method()).thenReturn(method);
        when(location.declaringType()).thenReturn(type);
        when(location.lineNumber()).thenReturn(line);
        when(location.codeIndex()).thenReturn(codeIndex);
        when(method.name()).thenReturn(name);
        when(method.signature()).thenReturn(descriptor);
        when(type.classLoader()).thenReturn(null);
        when(type.name()).thenReturn("fixture.Algorithm");
        return location;
    }

    private static int timesCalledInstalled(BreakpointRequest request) {
        verify(request).enable();
        return 1;
    }
}
