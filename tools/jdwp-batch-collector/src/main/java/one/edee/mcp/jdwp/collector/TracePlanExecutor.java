package one.edee.mcp.jdwp.collector;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.core.FrameSnapshotter;
import one.edee.mcp.jdwp.core.JdiValuePathReader;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 按确定性计划安装断点并消费目标 JVM 事件，直到 JVM 结束、空闲超时或达到事件预算。
 */
final class TracePlanExecutor {
    private static final String TRACEPOINT_PROPERTY = "collector.tracepoint";

    private final VirtualMachine vm;
    private final DebugPlan plan;
    private final JsonlTraceWriter writer;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong capturedEvents = new AtomicLong();
    private final Map<String, DebugPlan.Tracepoint> tracepointsById;
    private final StackFrameConditionEvaluator conditionEvaluator = new StackFrameConditionEvaluator();
    private final Map<String, Integer> observedHitCounts = new HashMap<>();
    private final Map<String, Integer> matchedHitCounts = new HashMap<>();
    private final Map<String, Integer> capturedHitCounts = new HashMap<>();
    private final Map<String, Integer> conditionUnavailableCounts = new HashMap<>();
    private final Map<String, Map<String, Integer>> conditionUnavailableReasons = new HashMap<>();
    private final Map<String, Integer> installedCounts = new HashMap<>();
    private final Map<String, List<BreakpointRequest>> requestsByTracepoint = new HashMap<>();
    private final Set<String> installedLocations = new HashSet<>();

    TracePlanExecutor(VirtualMachine vm, DebugPlan plan, JsonlTraceWriter writer) {
        this.vm = vm;
        this.plan = plan;
        this.writer = writer;
        this.tracepointsById = plan.tracepoints.stream().collect(Collectors.toUnmodifiableMap(
            tracepoint -> tracepoint.id,
            tracepoint -> tracepoint
        ));
    }

    CollectionResult execute() throws IOException {
        installTracepoints();
        writeLifecycle("collector_started", Map.of(
            "vmName", safeVmName(),
            "tracepointCount", plan.tracepoints.size()
        ));
        writer.flush();
        if (plan.resumeOnAttach) {
            vm.resume();
        }

        long lastEventAt = System.currentTimeMillis();
        String completion = "vm_disconnected";
        try {
            while (capturedEvents.get() < plan.maxEvents) {
                long remaining = plan.idleTimeoutMillis - (System.currentTimeMillis() - lastEventAt);
                if (remaining <= 0) {
                    completion = "idle_timeout";
                    break;
                }
                EventSet eventSet = vm.eventQueue().remove(Math.min(remaining, 1_000));
                if (eventSet == null) {
                    continue;
                }
                lastEventAt = System.currentTimeMillis();
                boolean terminal = false;
                List<Map<String, Object>> pendingWrites = new ArrayList<>();
                try {
                    for (Event event : eventSet) {
                        if (event instanceof BreakpointEvent breakpointEvent) {
                            Map<String, Object> captured = captureBreakpoint(breakpointEvent);
                            if (captured != null) pendingWrites.add(captured);
                        } else if (event instanceof ClassPrepareEvent classPrepareEvent) {
                            installForPreparedClass(classPrepareEvent.referenceType());
                        } else if (event instanceof VMDeathEvent) {
                            completion = "vm_death";
                            terminal = true;
                        } else if (event instanceof VMDisconnectEvent) {
                            completion = "vm_disconnect";
                            terminal = true;
                        }
                    }
                } finally {
                    try {
                        eventSet.resume();
                    } catch (VMDisconnectedException ignored) {
                        terminal = true;
                    }
                }
                for (Map<String, Object> pending : pendingWrites) {
                    writer.write(pending);
                }
                if (!pendingWrites.isEmpty()) writer.flush();
                if (terminal) {
                    break;
                }
            }
            if (capturedEvents.get() >= plan.maxEvents) {
                completion = "max_events";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            completion = "interrupted";
        } catch (VMDisconnectedException disconnected) {
            completion = "vm_disconnected";
        }
        writeLifecycle("collector_finished", Map.of("reason", completion));
        writer.flush();
        return new CollectionResult(
            completion,
            sequence.get(),
            Map.copyOf(observedHitCounts),
            Map.copyOf(matchedHitCounts),
            Map.copyOf(capturedHitCounts),
            Map.copyOf(conditionUnavailableCounts),
            immutableNestedCounters(conditionUnavailableReasons),
            Map.copyOf(installedCounts)
        );
    }

    private void installTracepoints() {
        EventRequestManager manager = vm.eventRequestManager();
        Map<String, List<DebugPlan.Tracepoint>> byClass = plan.tracepoints.stream()
            .collect(Collectors.groupingBy(
                tracepoint -> tracepoint.className,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        for (Map.Entry<String, List<DebugPlan.Tracepoint>> entry : byClass.entrySet()) {
            List<ReferenceType> loaded = vm.classesByName(entry.getKey());
            if (loaded.isEmpty()) {
                ClassPrepareRequest prepare = manager.createClassPrepareRequest();
                prepare.addClassFilter(entry.getKey());
                prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                prepare.enable();
            } else {
                for (ReferenceType type : loaded) {
                    installForPreparedClass(type);
                }
            }
        }
    }

    private void installForPreparedClass(ReferenceType type) {
        for (DebugPlan.Tracepoint tracepoint : plan.tracepoints) {
            if (tracepoint.className.equals(type.name())) {
                installAtLocations(tracepoint, type);
            }
        }
    }

    private void installAtLocations(DebugPlan.Tracepoint tracepoint, ReferenceType type) {
        try {
            List<Location> locations = type.locationsOfLine(tracepoint.line).stream()
                .filter(location -> methodMatches(tracepoint, location))
                .toList();
            for (Location location : locations) {
                String installKey = installKey(tracepoint, location);
                if (!installedLocations.add(installKey)) {
                    continue;
                }
                BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(location);
                request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                request.putProperty(TRACEPOINT_PROPERTY, tracepoint.id);
                request.enable();
                requestsByTracepoint.computeIfAbsent(tracepoint.id, ignored -> new ArrayList<>()).add(request);
                installedCounts.merge(tracepoint.id, 1, Integer::sum);
            }
            installedCounts.putIfAbsent(tracepoint.id, 0);
        } catch (AbsentInformationException noLines) {
            installedCounts.putIfAbsent(tracepoint.id, 0);
        }
    }

    private static boolean methodMatches(DebugPlan.Tracepoint tracepoint, Location location) {
        boolean nameMatches = tracepoint.methodName == null
            || tracepoint.methodName.isBlank()
            || tracepoint.methodName.equals(location.method().name());
        boolean descriptorMatches = tracepoint.methodDescriptor == null
            || tracepoint.methodDescriptor.isBlank()
            || tracepoint.methodDescriptor.equals(location.method().signature());
        return nameMatches && descriptorMatches;
    }

    private static String installKey(DebugPlan.Tracepoint tracepoint, Location location) {
        long loaderId = location.declaringType().classLoader() == null
            ? 0L
            : location.declaringType().classLoader().uniqueID();
        return tracepoint.id + '|' + loaderId + '|' + location.method().signature()
            + '|' + location.codeIndex();
    }

    private Map<String, Object> captureBreakpoint(BreakpointEvent event) {
        Object property = event.request().getProperty(TRACEPOINT_PROPERTY);
        if (!(property instanceof String tracepointId)) {
            return null;
        }
        DebugPlan.Tracepoint tracepoint = tracepointsById.get(tracepointId);
        if (tracepoint == null) {
            return null;
        }
        int observedHit = observedHitCounts.merge(tracepointId, 1, Integer::sum);
        if (observedHit > tracepoint.maxObservedHits) {
            disableTracepoint(tracepointId);
            return null;
        }
        StackFrameConditionEvaluator.Evaluation evaluation = conditionEvaluator.evaluate(
            event.thread(), tracepoint.conditions);
        if (evaluation.status() == StackFrameConditionEvaluator.Status.UNAVAILABLE) {
            conditionUnavailableCounts.merge(tracepointId, 1, Integer::sum);
            conditionUnavailableReasons.computeIfAbsent(tracepointId, ignored -> new HashMap<>())
                .merge(evaluation.reason(), 1, Integer::sum);
            disableAtObservationLimit(tracepointId, observedHit, tracepoint.maxObservedHits);
            return null;
        }
        if (evaluation.status() == StackFrameConditionEvaluator.Status.NOT_MATCHED) {
            disableAtObservationLimit(tracepointId, observedHit, tracepoint.maxObservedHits);
            return null;
        }
        int matchedHit = matchedHitCounts.merge(tracepointId, 1, Integer::sum);
        boolean captureSelected = matchedHit <= tracepoint.captureFirstMatchedHits
            || (tracepoint.captureEveryMatchedHits > 0
                && matchedHit % tracepoint.captureEveryMatchedHits == 0);
        if (!captureSelected) {
            disableAtObservationLimit(tracepointId, observedHit, tracepoint.maxObservedHits);
            return null;
        }
        int capturedHit = capturedHitCounts.getOrDefault(tracepointId, 0);
        if (capturedHit >= tracepoint.maxCapturedHits
                || capturedEvents.get() >= plan.maxEvents) {
            disableTracepoint(tracepointId);
            return null;
        }
        capturedHit = capturedHitCounts.merge(tracepointId, 1, Integer::sum);
        capturedEvents.incrementAndGet();

        ThreadReference thread = event.thread();
        Map<String, Object> data = baseEvent("tracepoint_hit");
        data.put("tracepointId", tracepointId);
        data.put("hit", observedHit);
        data.put("observedHit", observedHit);
        data.put("matchedHit", matchedHit);
        data.put("capturedHit", capturedHit);
        if (!tracepoint.conditions.isEmpty()) {
            data.put("conditionResult", "MATCHED");
        }
        data.put("thread", Map.of("id", thread.uniqueID(), "name", thread.name()));
        data.put("location", Map.of(
            "className", event.location().declaringType().name(),
            "methodName", event.location().method().name(),
            "methodDescriptor", event.location().method().signature(),
            "line", event.location().lineNumber(),
            "codeIndex", event.location().codeIndex()
        ));
        data.put("frames", tracepoint.capture.stack
            ? new FrameSnapshotter().capture(thread, tracepoint.capture.maxFrames)
            : List.of());
        data.put("projections", captureProjections(thread, tracepoint.capture));
        if (observedHit >= tracepoint.maxObservedHits
                || capturedHit >= tracepoint.maxCapturedHits) {
            disableTracepoint(tracepointId);
        }
        return data;
    }

    private void disableAtObservationLimit(
            String tracepointId, int observedHit, int maxObservedHits) {
        if (observedHit >= maxObservedHits) {
            disableTracepoint(tracepointId);
        }
    }

    private static List<JdiValuePathReader.Projection> captureProjections(
            ThreadReference thread, DebugPlan.Capture capture) {
        JdiValuePathReader reader = new JdiValuePathReader(capture.maxStringLength);
        try {
            var frame = thread.frame(0);
            return capture.valuePaths.stream().map(path -> reader.read(frame, path)).toList();
        } catch (IncompatibleThreadStateException failure) {
            return capture.valuePaths.stream()
                    .map(path -> JdiValuePathReader.Projection.unavailable(
                            path, "THREAD_STATE_UNAVAILABLE"))
                    .toList();
        }
    }

    private void disableTracepoint(String tracepointId) {
        for (BreakpointRequest request : requestsByTracepoint.getOrDefault(tracepointId, List.of())) {
            request.disable();
        }
    }

    private void writeLifecycle(String type, Map<String, Object> details) throws IOException {
        Map<String, Object> event = baseEvent(type);
        event.putAll(details);
        writer.write(event);
    }

    private Map<String, Object> baseEvent(String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", "3.0");
        event.put("sessionId", plan.sessionId);
        event.put("sequence", sequence.incrementAndGet());
        event.put("timestamp", Instant.now().toString());
        event.put("eventType", type);
        return event;
    }

    private String safeVmName() {
        try {
            return vm.name();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static Map<String, Map<String, Integer>> immutableNestedCounters(
            Map<String, Map<String, Integer>> values) {
        Map<String, Map<String, Integer>> copied = new LinkedHashMap<>();
        values.forEach((key, counters) -> copied.put(key, Map.copyOf(counters)));
        return Map.copyOf(copied);
    }

    record CollectionResult(
        String completionReason,
        long eventCount,
        Map<String, Integer> observedHitCounts,
        Map<String, Integer> matchedHitCounts,
        Map<String, Integer> capturedHitCounts,
        Map<String, Integer> conditionUnavailableCounts,
        Map<String, Map<String, Integer>> conditionUnavailableReasons,
        Map<String, Integer> installedLocations
    ) {
    }
}
