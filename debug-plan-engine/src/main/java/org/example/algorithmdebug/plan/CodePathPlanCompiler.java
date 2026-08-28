package org.example.algorithmdebug.plan;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 将模型给出的关键方法选择确定性编译成精确 CodePath 采集计划。 */
public final class CodePathPlanCompiler {

    /** 验证选择器属于当前目录，允许跨包，并按稳定 methodKey 排序。 */
    public CodePathCollectionPlan compile(MethodCatalog catalog, CodePathPlanRequest request) {
        String rationale = request.rationale().strip();
        if (rationale.isEmpty() || rationale.length() > 4_096) {
            throw new PlanCompilationException("CodePath 计划 rationale 必须在 1 到 4096 字符之间");
        }
        if (request.selectedMethodKeys().isEmpty() || request.selectedMethodKeys().size() > 50) {
            throw new PlanCompilationException("CodePath 计划必须选择 1 到 50 个方法");
        }
        Map<String, MethodCatalogEntry> entries = catalog.entries().stream().collect(
                Collectors.toMap(MethodCatalogEntry::methodKey, Function.identity()));
        LinkedHashSet<String> uniqueKeys = new LinkedHashSet<>(request.selectedMethodKeys());
        if (uniqueKeys.size() != request.selectedMethodKeys().size()) {
            throw new PlanCompilationException("selectedMethodKeys 不得重复");
        }
        Optional<String> scopeMethodKey = request.scopeMethodKey().map(String::strip);
        if (scopeMethodKey.isPresent() && scopeMethodKey.orElseThrow().isEmpty()) {
            throw new PlanCompilationException("scopeMethodKey must not be blank");
        }
        if (scopeMethodKey.isPresent() && !entries.containsKey(scopeMethodKey.orElseThrow())) {
            throw new PlanCompilationException(
                    "Scope method does not belong to the current MethodCatalog: "
                            + scopeMethodKey.orElseThrow());
        }
        if (scopeMethodKey.isPresent() && !uniqueKeys.contains(scopeMethodKey.orElseThrow())) {
            throw new PlanCompilationException("Scope method must also be selected");
        }
        List<MethodSelector> selectors = uniqueKeys.stream().map(key -> {
            MethodCatalogEntry entry = entries.get(key);
            if (entry == null) {
                throw new PlanCompilationException("选择的方法不属于当前 MethodCatalog: " + key);
            }
            var anchor = entry.sourceAnchor();
            return new MethodSelector(key, anchor.className(), anchor.methodName(), anchor.descriptor());
        }).sorted(Comparator.comparing(MethodSelector::methodKey)).toList();
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN,
                request.planId(), catalog.caseId(), catalog.contextId(), catalog.analysisId(),
                catalog.targetTest(), selectors, scopeMethodKey,
                request.budget(), rationale, request.requestedAt());
    }
}
