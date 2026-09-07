package org.example.algorithmdebug.codepath.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Advice 与同步采集 Sink 之间的单会话运行时。 */
public final class CodePathCaptureRuntime implements AutoCloseable {
    private static final long FOREIGN_THREAD = Long.MIN_VALUE;
    private static volatile CodePathCaptureRuntime current;

    private final LauncherCodePathPlan plan;
    private final Map<String, LauncherCodePathPlan.MethodSelection> selections;
    private final ScalarProjectionReader projectionReader = new ScalarProjectionReader();
    private final CodePathCaptureSink sink;
    private final Deque<CallFrame> calls = new ArrayDeque<>();
    private final Deque<ScopeFrame> scopes = new ArrayDeque<>();
    private final Set<String> conditionProjectionNames;
    private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
    private long selectedThreadId = -1;
    private long tokenSequence;
    private long observedScopes;
    private long matchedScopes;
    private long capturedScopes;
    private long skippedScopes;
    private long unavailableScopes;
    private long conditionProjectionReads;
    private long detailProjectionReads;
    private String failureCode;
    private String failureDetail;
    private boolean closed;
    private CaptureResult result;

    private CodePathCaptureRuntime(LauncherCodePathPlan plan, Path output) throws IOException {
        this.plan = plan;
        Map<String, LauncherCodePathPlan.MethodSelection> selected = new LinkedHashMap<>();
        for (var selection : plan.methodSelections()) {
            selected.put(selection.selector().methodKey(), selection);
        }
        this.selections = Map.copyOf(selected);
        this.conditionProjectionNames = plan.scopeConditions().stream()
                .map(LauncherCodePathPlan.ScopeCondition::projectionName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.sink = plan.captureMode() == LauncherCodePathPlan.CaptureMode.AGGREGATE
                ? new AggregateCaptureSink(output, plan.budget())
                : new DetailedTraceSink(output, plan.budget());
    }

    /** 激活唯一 Launcher 会话。 */
    static CodePathCaptureRuntime activate(LauncherCodePathPlan plan, Path output) throws IOException {
        if (current != null) throw new IllegalStateException("A CodePath capture session is already active");
        CodePathCaptureRuntime runtime = new CodePathCaptureRuntime(plan, output);
        current = runtime;
        return runtime;
    }

    /** 测试或进程清理时移除当前会话。 */
    static void deactivate() {
        current = null;
    }

    /** Advice 进入回调；任何采集失败都转换为工具状态，不传播到目标算法。 */
    public static long enter(
            String className, String methodName, String descriptor, Object[] arguments) {
        CodePathCaptureRuntime runtime = current;
        if (runtime == null) return FOREIGN_THREAD;
        try {
            return runtime.onEnter(className, methodName, descriptor,
                    arguments == null ? new Object[0] : arguments);
        } catch (RuntimeException | IOException failure) {
            runtime.fail("CODEPATH_CAPTURE_ENTER_FAILED", failure);
            return FOREIGN_THREAD;
        }
    }

    /** Advice 退出回调；异常退出仍执行栈闭合。 */
    public static void exit(
            String className, String methodName, String descriptor,
            Object returnValue, Throwable thrown, long token) {
        if (token == FOREIGN_THREAD) return;
        CodePathCaptureRuntime runtime = current;
        if (runtime == null) return;
        try {
            runtime.onExit(className, methodName, descriptor, returnValue, thrown, token);
        } catch (RuntimeException | IOException failure) {
            runtime.fail("CODEPATH_CAPTURE_EXIT_FAILED", failure);
        }
    }

    /** 由 AgentBuilder Listener 报告类转换失败。 */
    public static void instrumentationFailed(String typeName, Throwable failure) {
        CodePathCaptureRuntime runtime = current;
        if (runtime != null) runtime.fail(
                "CODEPATH_INSTRUMENTATION_FAILED", new IllegalStateException(typeName, failure));
    }

    private long onEnter(
            String className, String methodName, String descriptor, Object[] arguments)
            throws IOException {
        if (!selectThread()) return FOREIGN_THREAD;
        String methodKey = className + "#" + methodName + descriptor;
        LauncherCodePathPlan.MethodSelection selection = selections.get(methodKey);
        if (selection == null) {
            throw new IllegalStateException("Advice executed outside the CodePath Plan: " + methodKey);
        }
        long token = ++tokenSequence;
        int selectedDepth = calls.size();
        ScopeDecision scope = scopeDecision(methodKey, selection, arguments);
        boolean captured = scope.captured() && !sink.stopped();
        CallFrame parent = nearestCapturedFrame();
        List<ProjectionValue> values = List.of();
        long enterEventId = 0;
        if (captured) {
            values = entryValues(selection, arguments, scope.conditionValues());
            enterEventId = sink.enter(methodKey, selectedDepth,
                    parent == null ? null : parent.methodKey(),
                    parent == null ? null : parent.enterEventId(), values);
        }
        calls.addLast(new CallFrame(token, methodKey, selectedDepth, captured, enterEventId,
                scope.scopeEntry()));
        return token;
    }

    private void onExit(
            String className, String methodName, String descriptor,
            Object returnValue, Throwable thrown, long token) throws IOException {
        String methodKey = className + "#" + methodName + descriptor;
        CallFrame frame = calls.pollLast();
        if (frame == null || frame.token() != token || !frame.methodKey().equals(methodKey)) {
            reasons.add("TRACE_STRUCTURE_INCOMPLETE");
            failureCode = "CODEPATH_SELECTED_STACK_MISMATCH";
            failureDetail = failureCode;
            return;
        }
        if (frame.captured()) {
            LauncherCodePathPlan.MethodSelection selection = selections.get(methodKey);
            List<ProjectionValue> values = projectionReader.readReturn(
                    selection.projections(), returnValue, thrown);
            detailProjectionReads += values.size();
            sink.exit(methodKey, frame.selectedDepth(), frame.enterEventId(), values, thrown);
        }
        if (frame.scopeEntry()) {
            if (scopes.isEmpty()) {
                reasons.add("TRACE_STRUCTURE_INCOMPLETE");
                failureCode = "CODEPATH_SCOPE_STACK_MISMATCH";
                failureDetail = failureCode;
            } else {
                scopes.removeLast();
            }
        }
    }

    private ScopeDecision scopeDecision(
            String methodKey,
            LauncherCodePathPlan.MethodSelection selection,
            Object[] arguments) {
        boolean parentScopeCaptured = scopes.isEmpty() || scopes.peekLast().captured();
        if (plan.scopeMethodKey() == null) {
            return new ScopeDecision(true, false, List.of());
        }
        if (!plan.scopeMethodKey().equals(methodKey)) {
            return new ScopeDecision(!scopes.isEmpty() && parentScopeCaptured, false, List.of());
        }
        observedScopes++;
        List<ProjectionValue> conditionValues = projectionReader.readArguments(
                selection.projections(), arguments, conditionProjectionNames);
        conditionProjectionReads += conditionValues.size();
        MatchResult match = matches(conditionValues);
        if (match == MatchResult.UNAVAILABLE) unavailableScopes++;
        boolean matched = match == MatchResult.MATCHED && parentScopeCaptured;
        boolean captured = false;
        if (matched) {
            long matchedOrdinal = ++matchedScopes;
            long endExclusive = (long) plan.scopeStartOrdinal() + plan.maxMatchedScopes();
            captured = matchedOrdinal >= plan.scopeStartOrdinal() && matchedOrdinal < endExclusive;
            if (captured) capturedScopes++; else skippedScopes++;
        }
        scopes.addLast(new ScopeFrame(captured));
        return new ScopeDecision(captured, true, conditionValues);
    }

    private List<ProjectionValue> entryValues(
            LauncherCodePathPlan.MethodSelection selection,
            Object[] arguments,
            List<ProjectionValue> conditionValues) {
        if (selection.projections().isEmpty()) return List.of();
        if (conditionProjectionNames.isEmpty()) {
            List<ProjectionValue> values = projectionReader.readArguments(
                    selection.projections(), arguments);
            detailProjectionReads += values.size();
            return values;
        }
        List<ProjectionValue> remaining = projectionReader.readArguments(
                selection.projections(), arguments,
                selection.projections().stream().map(LauncherCodePathPlan.Projection::name)
                        .filter(name -> !conditionProjectionNames.contains(name))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        detailProjectionReads += remaining.size();
        Map<String, ProjectionValue> values = new LinkedHashMap<>();
        conditionValues.forEach(value -> values.put(value.name(), value));
        remaining.forEach(value -> values.put(value.name(), value));
        return selection.projections().stream().filter(value ->
                        value.source() == LauncherCodePathPlan.ProjectionSource.ARGUMENT)
                .map(value -> values.get(value.name())).filter(java.util.Objects::nonNull).toList();
    }

    private MatchResult matches(List<ProjectionValue> values) {
        if (plan.scopeConditions().isEmpty()) return MatchResult.MATCHED;
        Map<String, ProjectionValue> byName = new LinkedHashMap<>();
        values.forEach(value -> byName.put(value.name(), value));
        for (LauncherCodePathPlan.ScopeCondition condition : plan.scopeConditions()) {
            ProjectionValue actual = byName.get(condition.projectionName());
            if (actual == null || actual.status() == ProjectionStatus.UNAVAILABLE
                    || actual.status() == ProjectionStatus.TRUNCATED) return MatchResult.UNAVAILABLE;
            if (!ScalarValueMatcher.matches(condition, actual)) return MatchResult.NOT_MATCHED;
        }
        return MatchResult.MATCHED;
    }

    private boolean selectThread() {
        long threadId = Thread.currentThread().getId();
        if (selectedThreadId == -1) selectedThreadId = threadId;
        if (selectedThreadId == threadId) return true;
        failureCode = "CODEPATH_MULTIPLE_THREADS_UNSUPPORTED";
        failureDetail = failureCode;
        return false;
    }

    private CallFrame nearestCapturedFrame() {
        var iterator = calls.descendingIterator();
        while (iterator.hasNext()) {
            CallFrame frame = iterator.next();
            if (frame.captured()) return frame;
        }
        return null;
    }

    private void fail(String code, Throwable failure) {
        if (failureCode == null) {
            failureCode = code;
            String message = failure.getMessage();
            failureDetail = code + ": " + failure.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            failure.printStackTrace(System.err);
        }
        reasons.add(code);
    }

    String failureCode() { return failureCode; }
    String failureDetail() { return failureDetail; }

    CaptureResult result() {
        if (result != null) return result;
        return snapshotResult();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (!calls.isEmpty() || !scopes.isEmpty()) reasons.add("TRACE_STRUCTURE_INCOMPLETE");
        sink.scopeStatistics(new CodePathCaptureSink.ScopeCaptureStatistics(
                plan.scopeMethodKey() != null, plan.scopeMethodKey(), observedScopes,
                matchedScopes, capturedScopes, skippedScopes, unavailableScopes,
                scopes.stream().filter(ScopeFrame::captured).count()));
        sink.runtimeReasons(List.copyOf(reasons));
        sink.close();
        reasons.addAll(sink.reasons());
        result = snapshotResult();
        if (current == this) current = null;
    }

    private CaptureResult snapshotResult() {
        return new CaptureResult(
                sink.eventsWritten(), sink.bytesWritten(), sink.limit(),
                observedScopes, matchedScopes, capturedScopes, skippedScopes,
                unavailableScopes, conditionProjectionReads, detailProjectionReads,
                List.copyOf(reasons));
    }

    record CaptureResult(
            long eventsWritten,
            long bytesWritten,
            TraceJsonlSink.Limit limit,
            long scopeInvocationsObserved,
            long scopeInvocationsMatched,
            long scopeInvocationsCaptured,
            long scopeInvocationsSkippedByWindow,
            long scopeConditionUnavailableInvocations,
            long conditionProjectionReads,
            long detailProjectionReads,
            List<String> reasons) {}

    private record CallFrame(
            long token, String methodKey, int selectedDepth,
            boolean captured, long enterEventId, boolean scopeEntry) {}
    private record ScopeFrame(boolean captured) {}
    private record ScopeDecision(
            boolean captured, boolean scopeEntry, List<ProjectionValue> conditionValues) {}
    private enum MatchResult { MATCHED, NOT_MATCHED, UNAVAILABLE }
}
