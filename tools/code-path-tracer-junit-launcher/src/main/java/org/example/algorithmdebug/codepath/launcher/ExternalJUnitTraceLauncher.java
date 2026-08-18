package org.example.algorithmdebug.codepath.launcher;

import io.github.takahirom.codepathtracer.CodePathTracer;
import io.github.takahirom.codepathtracer.CodePathTracerAgent;
import io.github.takahirom.codepathtracer.TraceEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/** 读取归档精确计划并受控运行一个 JUnit 方法的 CodePath Launcher。 */
public final class ExternalJUnitTraceLauncher {
    private ExternalJUnitTraceLauncher() {}

    /** 进程入口；目标失败和工具失败通过结构化 Summary 分开报告。 */
    public static void main(String[] args) {
        LauncherSummary summary;
        try {
            LauncherArguments arguments = LauncherArguments.parse(args);
            summary = execute(arguments, new CodePathPlanReader().read(arguments.planFile()));
        } catch (Exception failure) {
            summary = new LauncherSummary(
                    LauncherOutcome.TOOL_FAILED, 0, 0, 0, 0, 0, 0,
                    TraceJsonlSink.Limit.NONE, bounded(failure));
        }
        System.out.println(summary.toStructuredLine());
        if (summary.outcome() == LauncherOutcome.TOOL_FAILED) System.exit(1);
        if (summary.outcome() == LauncherOutcome.TARGET_FAILED) System.exit(2);
    }

    private static LauncherSummary execute(
            LauncherArguments arguments, CodePathCollectionPlan plan) throws Exception {
        AtomicLong sequence = new AtomicLong();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        AtomicBoolean captureStopped = new AtomicBoolean();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        PlannedTraceEventGenerator generator = new PlannedTraceEventGenerator(plan);
        TraceJsonlSink.Result sinkResult;

        try (TraceJsonlSink sink = new TraceJsonlSink(
                arguments.traceFile(), plan.budget().maxBytes(), plan.budget().maxEvents())) {
            CodePathTracerAgent.INSTANCE.ensureInstalled();
            CodePathTracer tracer = new CodePathTracer.Builder()
                    .traceEventGenerator(advice -> captureStopped.get() ? null : generator.generate(advice))
                    .filter(event -> !captureStopped.get() && generator.matches(event))
                    .formatter(event -> jsonEvent(sequence.incrementAndGet(), event, generator))
                    .logger(line -> {
                        try {
                            if (!sink.append(line) || sink.limitReached()) captureStopped.set(true);
                        }
                        catch (IOException failure) { writeFailure.compareAndSet(null, failure); }
                        return Unit.INSTANCE;
                    })
                    .maxToStringLength(0)
                    .maxIndentDepth(1)
                    .build();
            tracer.trace(() -> {
                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectMethod(plan.targetTest().selector()))
                        .build();
                Launcher launcher = LauncherFactory.create();
                launcher.registerTestExecutionListeners(listener);
                launcher.execute(request);
                return null;
            });
            sinkResult = sink.result();
        }

        TestExecutionSummary junit = listener.getSummary();
        if (!junit.getFailures().isEmpty()) junit.printFailuresTo(new PrintWriter(System.err, true));
        String generatorFailure = generator.failureCode();
        LauncherOutcome outcome = generatorFailure == null
                ? LauncherResultClassifier.classify(
                        junit.getTestsFoundCount(), junit.getTestsFailedCount(),
                        junit.getTestsAbortedCount(), Optional.ofNullable(writeFailure.get()))
                : LauncherOutcome.TOOL_FAILED;
        String detail = generatorFailure != null ? generatorFailure
                : writeFailure.get() == null ? "" : bounded(writeFailure.get());
        return new LauncherSummary(
                outcome, junit.getTestsFoundCount(), junit.getTestsSucceededCount(),
                junit.getTestsAbortedCount(), junit.getTestsFailedCount(),
                sinkResult.eventsWritten(), sinkResult.bytesWritten(), sinkResult.limit(), detail);
    }

    private static String jsonEvent(
            long eventId, TraceEvent event, PlannedTraceEventGenerator generator) {
        return "{\"eventId\":" + eventId
                + ",\"eventType\":\"" + generator.eventType(event)
                + "\",\"depth\":" + event.getDepth()
                + ",\"className\":\"" + escape(event.getClassName())
                + "\",\"methodName\":\"" + escape(generator.methodName(event))
                + "\",\"descriptor\":\"" + escape(generator.descriptor(event)) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String bounded(Throwable failure) {
        String message = failure.getClass().getSimpleName() + ": "
                + Optional.ofNullable(failure.getMessage()).orElse("no detail");
        return message.length() <= 2_048 ? message : message.substring(0, 2_048);
    }
}
