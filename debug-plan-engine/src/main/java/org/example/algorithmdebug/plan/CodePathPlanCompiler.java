package org.example.algorithmdebug.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CodePathMethodSelection;
import org.example.algorithmdebug.contracts.CodePathProjection;
import org.example.algorithmdebug.contracts.CodePathProjectionSource;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 将大模型选择的方法和可读投影确定性编译成精确 CodePath 计划。 */
public final class CodePathPlanCompiler {

    private static final Pattern ARGUMENT_PATH = Pattern.compile("arg\\[(\\d+)]((?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)");
    private static final Pattern RETURN_PATH = Pattern.compile("return((?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)");

    /** 验证方法属于当前目录、投影符合 descriptor，并生成稳定排序的 Plan。 */
    public CodePathCollectionPlan compile(MethodCatalog catalog, CodePathPlanRequest request) {
        if (request.methods().isEmpty() || request.methods().size() > 50) {
            throw new PlanCompilationException("The CodePath plan must select between 1 and 50 methods");
        }
        Map<String, MethodCatalogEntry> entries = catalog.entries().stream().collect(
                Collectors.toMap(MethodCatalogEntry::methodKey, Function.identity()));
        LinkedHashSet<String> uniqueKeys = request.methods().stream()
                .map(CodePathMethodRequest::methodKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueKeys.size() != request.methods().size()) {
            throw new PlanCompilationException("methods must not contain duplicate methodKey");
        }
        Optional<String> scopeMethodKey = validateScope(request, entries, uniqueKeys);
        List<CodePathMethodSelection> selections = request.methods().stream()
                .map(method -> compileMethod(entries, method))
                .sorted(Comparator.comparing(selection -> selection.selector().methodKey()))
                .toList();
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN,
                request.planId(), catalog.caseId(), catalog.analysisId(), catalog.targetTest(),
                selections, scopeMethodKey, request.budget(), request.rationale(),
                request.intent(), request.requestedAt());
    }

    private Optional<String> validateScope(
            CodePathPlanRequest request,
            Map<String, MethodCatalogEntry> entries,
            LinkedHashSet<String> uniqueKeys) {
        Optional<String> scope = request.scopeMethodKey().map(String::strip);
        if (scope.isPresent() && scope.orElseThrow().isEmpty()) {
            throw new PlanCompilationException("scopeMethodKey must not be blank");
        }
        if (scope.isPresent() && !entries.containsKey(scope.orElseThrow())) {
            throw new PlanCompilationException(
                    "Scope method does not belong to the current MethodCatalog: " + scope.orElseThrow());
        }
        if (scope.isPresent() && !uniqueKeys.contains(scope.orElseThrow())) {
            throw new PlanCompilationException("Scope method must also be selected");
        }
        return scope;
    }

    private CodePathMethodSelection compileMethod(
            Map<String, MethodCatalogEntry> entries,
            CodePathMethodRequest request) {
        MethodCatalogEntry entry = entries.get(request.methodKey());
        if (entry == null) {
            throw new PlanCompilationException(
                    "The selected method does not belong to the current MethodCatalog: " + request.methodKey());
        }
        HashSet<String> projectionNames = new HashSet<>();
        if (request.projections().stream().anyMatch(projection -> !projectionNames.add(projection.name()))) {
            throw new PlanCompilationException("Projection names must be unique within a method");
        }
        DescriptorShape shape = parseDescriptor(entry.sourceAnchor().descriptor());
        List<CodePathProjection> projections = request.projections().stream()
                .map(projection -> compileProjection(projection, shape))
                .toList();
        var anchor = entry.sourceAnchor();
        MethodSelector selector = new MethodSelector(
                request.methodKey(), anchor.className(), anchor.methodName(), anchor.descriptor());
        return new CodePathMethodSelection(selector, projections);
    }

    private CodePathProjection compileProjection(
            CodePathProjectionRequest request,
            DescriptorShape descriptor) {
        Matcher argument = ARGUMENT_PATH.matcher(request.path());
        if (argument.matches()) {
            int index;
            try {
                index = Integer.parseInt(argument.group(1));
            } catch (NumberFormatException failure) {
                throw new PlanCompilationException("Invalid argument index in projection path: " + request.path());
            }
            if (index >= descriptor.argumentCount()) {
                throw new PlanCompilationException("Projection argument index is outside the method descriptor: " + request.path());
            }
            return projection(request, CodePathProjectionSource.ARGUMENT, Optional.of(index), argument.group(2));
        }
        Matcher returned = RETURN_PATH.matcher(request.path());
        if (returned.matches()) {
            if (descriptor.voidReturn()) {
                throw new PlanCompilationException("A void method cannot define a return projection");
            }
            return projection(request, CodePathProjectionSource.RETURN, Optional.empty(), returned.group(1));
        }
        throw new PlanCompilationException(
                "Projection path must use arg[n](.field)* or return(.field)*: " + request.path());
    }

    private CodePathProjection projection(
            CodePathProjectionRequest request,
            CodePathProjectionSource source,
            Optional<Integer> argumentIndex,
            String suffix) {
        List<String> fields = suffix.isEmpty()
                ? List.of()
                : List.of(suffix.substring(1).split("\\."));
        try {
            return new CodePathProjection(request.name(), source, argumentIndex, fields, request.required());
        } catch (IllegalArgumentException failure) {
            throw new PlanCompilationException("Invalid projection '" + request.name() + "': " + failure.getMessage());
        }
    }

    private DescriptorShape parseDescriptor(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != '(') {
            throw new PlanCompilationException("Invalid JVM method descriptor");
        }
        int cursor = 1;
        int argumentCount = 0;
        while (cursor < descriptor.length() && descriptor.charAt(cursor) != ')') {
            cursor = skipType(descriptor, cursor, false);
            argumentCount++;
        }
        if (cursor >= descriptor.length() - 1 || descriptor.charAt(cursor) != ')') {
            throw new PlanCompilationException("Invalid JVM method descriptor");
        }
        int returnStart = cursor + 1;
        int end = skipType(descriptor, returnStart, true);
        if (end != descriptor.length()) {
            throw new PlanCompilationException("Invalid JVM method descriptor");
        }
        return new DescriptorShape(argumentCount, descriptor.charAt(returnStart) == 'V');
    }

    private int skipType(String descriptor, int start, boolean allowVoid) {
        int cursor = start;
        while (cursor < descriptor.length() && descriptor.charAt(cursor) == '[') {
            cursor++;
        }
        if (cursor >= descriptor.length()) {
            throw new PlanCompilationException("Invalid JVM method descriptor");
        }
        char type = descriptor.charAt(cursor);
        if (type == 'L') {
            int end = descriptor.indexOf(';', cursor);
            if (end < 0 || end == cursor + 1) {
                throw new PlanCompilationException("Invalid JVM method descriptor");
            }
            return end + 1;
        }
        if ("BCDFIJSZ".indexOf(type) >= 0 || (allowVoid && cursor == start && type == 'V')) {
            return cursor + 1;
        }
        throw new PlanCompilationException("Invalid JVM method descriptor");
    }

    private record DescriptorShape(int argumentCount, boolean voidReturn) {
    }
}
