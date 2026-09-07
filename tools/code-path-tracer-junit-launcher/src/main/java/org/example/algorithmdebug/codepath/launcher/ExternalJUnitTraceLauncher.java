package org.example.algorithmdebug.codepath.launcher;

import java.io.PrintWriter;
import java.util.Optional;
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
            LauncherArguments arguments, LauncherCodePathPlan plan) throws Exception {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        CodePathCaptureRuntime runtime = CodePathCaptureRuntime.activate(plan, arguments.traceFile());
        PlanAwareCodePathAgent.Installation installation = PlanAwareCodePathAgent.install(plan);
        try (runtime) {
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectMethod(plan.targetTest().selector()))
                    .build();
            Launcher launcher = LauncherFactory.create();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(request);
        }

        TestExecutionSummary junit = listener.getSummary();
        if (!junit.getFailures().isEmpty()) junit.printFailuresTo(new PrintWriter(System.err, true));
        String captureFailure = runtime.failureCode();
        LauncherOutcome outcome = captureFailure == null
                ? LauncherResultClassifier.classify(
                        junit.getTestsFoundCount(), junit.getTestsFailedCount(),
                        junit.getTestsAbortedCount(), Optional.empty())
                : LauncherOutcome.TOOL_FAILED;
        String detail = captureFailure == null ? "" : runtime.failureDetail();
        CodePathCaptureRuntime.CaptureResult result = runtime.result();
        return new LauncherSummary(
                outcome, junit.getTestsFoundCount(), junit.getTestsSucceededCount(),
                junit.getTestsAbortedCount(), junit.getTestsFailedCount(),
                result.eventsWritten(), result.bytesWritten(), result.limit(), detail,
                plan.captureMode().name(), "SELECTED_METHOD_DEPTH",
                installation.selectedTypes(), installation.selectedMethods(),
                plan.scopeMethodKey() != null,
                result.scopeInvocationsObserved(), result.scopeInvocationsMatched(),
                result.scopeInvocationsCaptured(), result.scopeInvocationsSkippedByWindow(),
                result.scopeConditionUnavailableInvocations(), result.conditionProjectionReads(),
                result.detailProjectionReads(), result.reasons());
    }

    private static String bounded(Throwable failure) {
        String message = failure.getClass().getSimpleName() + ": "
                + Optional.ofNullable(failure.getMessage()).orElse("no detail");
        return message.length() <= 2_048 ? message : message.substring(0, 2_048);
    }
}
