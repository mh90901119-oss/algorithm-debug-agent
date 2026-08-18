package org.example.algorithmdebug.codepath.launcher;

import io.github.takahirom.codepathtracer.CodePathTracer;
import io.github.takahirom.codepathtracer.CodePathTracerAgent;
import io.github.takahirom.codepathtracer.TraceEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import org.example.algorithmdebug.contracts.JavaPackageScope;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Agent 自有的受控 CodePath JUnit Launcher。
 *
 * <p>它在 JUnit 加载目标类前安装上游 Agent，以 package 边界捕获事件，并直接流式写盘。达到预算只停止
 * 记录，目标 UT 始终继续；最终 stdout 只追加一条带稳定前缀的结构化 Summary。</p>
 */
public final class ExternalJUnitTraceLauncher {
    private ExternalJUnitTraceLauncher() {
    }

    /** 进程入口。退出码只用于进程管理，父进程必须读取结构化 Summary 判断目标状态。 */
    public static void main(String[] args) {
        LauncherSummary summary;
        try {
            summary = execute(LauncherArguments.parse(args));
        } catch (Exception failure) {
            summary = new LauncherSummary(
                    LauncherOutcome.TOOL_FAILED, 0, 0, 0, 0, 0, 0,
                    TraceJsonlSink.Limit.NONE, bounded(failure));
        }
        System.out.println(summary.toStructuredLine());
        if (summary.outcome() == LauncherOutcome.TOOL_FAILED) {
            System.exit(1);
        }
        if (summary.outcome() == LauncherOutcome.TARGET_FAILED) {
            System.exit(2);
        }
    }

    private static LauncherSummary execute(LauncherArguments arguments) throws Exception {
        AtomicLong sequence = new AtomicLong();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        TraceJsonlSink.Result sinkResult;

        try (TraceJsonlSink sink = new TraceJsonlSink(
                arguments.traceFile(), arguments.maxOutputBytes(), arguments.maxEvents())) {
            // 必须早于 JUnit discovery；否则目标类可能先被 JVM 加载而无法追踪。
            CodePathTracerAgent.INSTANCE.ensureInstalled();
            CodePathTracer tracer = new CodePathTracer.Builder()
                    .filter(event -> includes(arguments.includePackage(), event.getClassName()))
                    .formatter(event -> jsonEvent(sequence.incrementAndGet(), event))
                    .logger(line -> {
                        try {
                            sink.append(line);
                        } catch (IOException failure) {
                            writeFailure.compareAndSet(null, failure);
                        }
                        return Unit.INSTANCE;
                    })
                    .maxToStringLength(80)
                    .maxIndentDepth(100)
                    .build();

            tracer.trace(() -> {
                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectMethod(arguments.testSelector()))
                        .build();
                Launcher launcher = LauncherFactory.create();
                launcher.registerTestExecutionListeners(listener);
                launcher.execute(request);
                return null;
            });
            sinkResult = sink.result();
        }

        TestExecutionSummary junit = listener.getSummary();
        if (!junit.getFailures().isEmpty()) {
            junit.printFailuresTo(new PrintWriter(System.err, true));
        }
        LauncherOutcome outcome = LauncherResultClassifier.classify(
                junit.getTestsFoundCount(), junit.getTestsFailedCount(),
                junit.getTestsAbortedCount(), Optional.ofNullable(writeFailure.get()));
        return new LauncherSummary(
                outcome, junit.getTestsFoundCount(), junit.getTestsSucceededCount(),
                junit.getTestsAbortedCount(), junit.getTestsFailedCount(),
                sinkResult.eventsWritten(), sinkResult.bytesWritten(), sinkResult.limit(),
                writeFailure.get() == null ? "" : bounded(writeFailure.get()));
    }

    private static boolean includes(String rootPackage, String className) {
        int separator = className.lastIndexOf('.');
        return separator > 0
                && JavaPackageScope.contains(rootPackage, className.substring(0, separator));
    }

    private static String jsonEvent(long eventId, TraceEvent event) {
        String eventType = event instanceof TraceEvent.Enter ? "METHOD_ENTER" : "METHOD_EXIT";
        return "{\"eventId\":" + eventId
                + ",\"eventType\":\"" + eventType
                + "\",\"depth\":" + event.getDepth()
                + ",\"threadName\":\"" + escape(Thread.currentThread().getName())
                + "\",\"className\":\"" + escape(event.getClassName())
                + "\",\"methodName\":\"" + escape(event.getMethodName()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String bounded(Throwable failure) {
        String message = failure.getClass().getSimpleName() + ": "
                + Optional.ofNullable(failure.getMessage()).orElse("no detail");
        return message.length() <= 2_048 ? message : message.substring(0, 2_048);
    }
}
