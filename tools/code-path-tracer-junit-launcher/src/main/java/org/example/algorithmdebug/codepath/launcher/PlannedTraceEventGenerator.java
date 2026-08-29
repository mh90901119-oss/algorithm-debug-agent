package org.example.algorithmdebug.codepath.launcher;

import io.github.takahirom.codepathtracer.AdviceData;
import io.github.takahirom.codepathtracer.TraceEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** 在上游格式化前按 class + method + descriptor 精确选择事件，并丢弃参数和返回值。 */
final class PlannedTraceEventGenerator {
    private static final char DESCRIPTOR_SEPARATOR = '\0';

    private final Set<Identity> selectors;
    private final AtomicLong selectedThread = new AtomicLong(-1);
    private final AtomicReference<String> failureCode = new AtomicReference<>();

    PlannedTraceEventGenerator(LauncherCodePathPlan plan) {
        selectors = plan.selectors().stream()
                .map(value -> new Identity(value.className(), value.methodName(), value.descriptor()))
                .collect(Collectors.toUnmodifiableSet());
    }

    TraceEvent generate(AdviceData data) {
        if (failureCode.get() != null) return null;
        String className;
        String methodName;
        String descriptor;
        int depth;
        boolean enter;
        if (data instanceof AdviceData.Enter value) {
            className = value.getClazz().getName(); methodName = value.getMethodName();
            descriptor = value.getDescriptor(); depth = value.getDepth(); enter = true;
        } else if (data instanceof AdviceData.Exit value) {
            className = value.getClazz().getName(); methodName = value.getMethodName();
            descriptor = value.getDescriptor(); depth = value.getDepth(); enter = false;
        } else {
            return null;
        }
        if (!selectors.contains(new Identity(className, methodName, descriptor))) return null;
        long current = Thread.currentThread().getId();
        long owner = selectedThread.updateAndGet(existing -> existing == -1 ? current : existing);
        if (owner != current) {
            failureCode.compareAndSet(null, "CODEPATH_MULTIPLE_THREADS_UNSUPPORTED");
            return null;
        }
        String encodedMethod = methodName + DESCRIPTOR_SEPARATOR + descriptor;
        return enter
                ? new TraceEvent.Enter(className, encodedMethod, new Object[0], depth, List.of())
                : new TraceEvent.Exit(className, encodedMethod, null, depth, List.of());
    }

    boolean matches(TraceEvent event) {
        return event.getMethodName().indexOf(DESCRIPTOR_SEPARATOR) >= 0;
    }

    String methodName(TraceEvent event) {
        String value = event.getMethodName();
        int separator = value.indexOf(DESCRIPTOR_SEPARATOR);
        if (separator < 1) throw new IllegalArgumentException("A planned-method marker is missing");
        return value.substring(0, separator);
    }

    String descriptor(TraceEvent event) {
        String value = event.getMethodName();
        int separator = value.indexOf(DESCRIPTOR_SEPARATOR);
        if (separator < 1 || separator == value.length() - 1) {
            throw new IllegalArgumentException("Missing JVM descriptor");
        }
        return value.substring(separator + 1);
    }

    String eventType(TraceEvent event) {
        return event instanceof TraceEvent.Enter ? "METHOD_ENTER" : "METHOD_EXIT";
    }

    String failureCode() { return failureCode.get(); }

    private record Identity(String className, String methodName, String descriptor) {}
}
