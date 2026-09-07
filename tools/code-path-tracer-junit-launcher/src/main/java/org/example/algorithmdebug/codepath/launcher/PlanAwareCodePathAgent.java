package org.example.algorithmdebug.codepath.launcher;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

/** 仅对 Plan 精确选择的方法安装 Byte Buddy Advice。 */
final class PlanAwareCodePathAgent {
    private PlanAwareCodePathAgent() {}

    static Installation install(LauncherCodePathPlan plan) {
        Map<String, List<LauncherCodePathPlan.MethodSelection>> byClass = new LinkedHashMap<>();
        for (var selection : plan.methodSelections()) {
            byClass.computeIfAbsent(selection.selector().className(), ignored -> new ArrayList<>())
                    .add(selection);
        }
        Instrumentation instrumentation = ByteBuddyAgent.install();
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(
                            String typeName, ClassLoader classLoader,
                            net.bytebuddy.utility.JavaModule module, boolean loaded,
                            Throwable throwable) {
                        CodePathCaptureRuntime.instrumentationFailed(typeName, throwable);
                    }
                });
        for (Map.Entry<String, List<LauncherCodePathPlan.MethodSelection>> entry : byClass.entrySet()) {
            List<LauncherCodePathPlan.MethodSelection> selections = List.copyOf(entry.getValue());
            builder = builder.type(named(entry.getKey())).transform(
                    (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                            applySelections(typeBuilder, selections));
        }
        builder.installOn(instrumentation);
        return new Installation(byClass.size(), plan.methodSelections().size());
    }

    private static DynamicType.Builder<?> applySelections(
            DynamicType.Builder<?> builder,
            List<LauncherCodePathPlan.MethodSelection> selections) {
        ElementMatcher.Junction<MethodDescription> pathOnly = none();
        ElementMatcher.Junction<MethodDescription> arguments = none();
        ElementMatcher.Junction<MethodDescription> returns = none();
        for (var selection : selections) {
            ElementMatcher.Junction<MethodDescription> exact = named(selection.selector().methodName())
                    .and(hasDescriptor(selection.selector().descriptor()));
            boolean hasReturn = selection.projections().stream().anyMatch(value ->
                    value.source() == LauncherCodePathPlan.ProjectionSource.RETURN);
            if (selection.projections().isEmpty()) pathOnly = pathOnly.or(exact);
            else if (hasReturn) returns = returns.or(exact);
            else arguments = arguments.or(exact);
        }
        DynamicType.Builder<?> result = builder;
        if (!pathOnly.equals(none())) result = result.visit(Advice.to(PathOnlyTraceAdvice.class).on(pathOnly));
        if (!arguments.equals(none())) result = result.visit(Advice.to(ArgumentTraceAdvice.class).on(arguments));
        if (!returns.equals(none())) result = result.visit(Advice.to(ReturnTraceAdvice.class).on(returns));
        return result;
    }

    record Installation(int selectedTypes, int selectedMethods) {}
}
