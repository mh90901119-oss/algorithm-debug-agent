package org.example.algorithmdebug.codepath.launcher;

import io.github.takahirom.codepathtracer.AdviceData;
import io.github.takahirom.codepathtracer.TraceEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 只接受 Plan 精确选择的方法，并在事件线程立即生成标量快照。 */
final class PlannedTraceEventGenerator {
    private final Map<Identity, LauncherCodePathPlan.MethodSelection> selections;
    private final ScalarProjectionReader projectionReader;
    private final AtomicLong selectedThread = new AtomicLong(-1);
    private final AtomicReference<String> failureCode = new AtomicReference<>();

    PlannedTraceEventGenerator(LauncherCodePathPlan plan) {
        this(plan, new ScalarProjectionReader());
    }

    PlannedTraceEventGenerator(LauncherCodePathPlan plan, ScalarProjectionReader projectionReader) {
        selections = plan.methodSelections().stream().collect(Collectors.toUnmodifiableMap(
                selection -> new Identity(
                        selection.selector().className(), selection.selector().methodName(),
                        selection.selector().descriptor()),
                Function.identity()));
        this.projectionReader = projectionReader;
    }

    TraceEvent generate(AdviceData data) {
        if (failureCode.get() != null) return null;
        Identity identity = identity(data);
        LauncherCodePathPlan.MethodSelection selection = selections.get(identity);
        if (selection == null) return null;
        if (!selectCurrentThread()) return null;
        if (data instanceof AdviceData.Enter enter) {
            CapturedProjectionValues values = new CapturedProjectionValues(
                    enter.getDescriptor(), projectionReader.readArguments(selection.projections(), enter.getArgs()));
            return new TraceEvent.Enter(
                    identity.className(), identity.methodName(), new Object[]{values},
                    enter.getDepth(), List.of());
        }
        if (data instanceof AdviceData.Exit exit) {
            CapturedProjectionValues values = new CapturedProjectionValues(
                    exit.getDescriptor(), projectionReader.readReturn(selection.projections(), exit.getReturnValue()));
            return new TraceEvent.Exit(
                    identity.className(), identity.methodName(), values,
                    exit.getDepth(), List.of());
        }
        return null;
    }

    String failureCode() {
        return failureCode.get();
    }

    boolean matches(TraceEvent event) {
        return selections.containsKey(new Identity(
                event.getClassName(), methodName(event), descriptor(event)));
    }

    String eventType(TraceEvent event) {
        return event instanceof TraceEvent.Enter ? "METHOD_ENTER" : "METHOD_EXIT";
    }

    String methodName(TraceEvent event) {
        if (event instanceof TraceEvent.Enter enter) return enter.getMethodName();
        if (event instanceof TraceEvent.Exit exit) return exit.getMethodName();
        return "";
    }

    String descriptor(TraceEvent event) {
        CapturedProjectionValues captured = captured(event);
        return captured == null ? "" : captured.descriptor();
    }

    List<ProjectionValue> projections(TraceEvent event) {
        CapturedProjectionValues captured = captured(event);
        return captured == null ? List.of() : captured.projections();
    }

    private boolean selectCurrentThread() {
        long threadId = Thread.currentThread().getId();
        long selected = selectedThread.get();
        if (selected == -1 && selectedThread.compareAndSet(-1, threadId)) return true;
        if (selectedThread.get() == threadId) return true;
        failureCode.compareAndSet(null, "CODEPATH_MULTIPLE_THREADS_UNSUPPORTED");
        return false;
    }

    private Identity identity(AdviceData data) {
        if (data instanceof AdviceData.Enter enter) {
            return new Identity(enter.getClazz().getName(), enter.getMethodName(), enter.getDescriptor());
        }
        if (data instanceof AdviceData.Exit exit) {
            return new Identity(exit.getClazz().getName(), exit.getMethodName(), exit.getDescriptor());
        }
        return new Identity("", "", "");
    }

    private CapturedProjectionValues captured(TraceEvent event) {
        if (event instanceof TraceEvent.Enter enter) {
            Object[] arguments = enter.getArgs();
            return arguments.length == 1 && arguments[0] instanceof CapturedProjectionValues captured
                    ? captured : null;
        }
        if (event instanceof TraceEvent.Exit exit
                && exit.getReturnValue() instanceof CapturedProjectionValues captured) {
            return captured;
        }
        return null;
    }

    private record Identity(String className, String methodName, String descriptor) {
    }
}
