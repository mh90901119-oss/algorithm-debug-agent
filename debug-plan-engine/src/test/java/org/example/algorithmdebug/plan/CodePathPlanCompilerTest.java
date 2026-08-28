package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;

class CodePathPlanCompilerTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void compilesStableExactSelectorsAcrossPackages() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.internal.Service", "solve", "(I)I", 1)));

        CodePathCollectionPlan plan = new CodePathPlanCompiler().compile(catalog, request(List.of(
                "fixture.internal.Service#solve(I)I",
                "fixture.TargetTest#caseUnderTest()V")));

        assertEquals(List.of("fixture.TargetTest", "fixture.internal.Service"),
                plan.selectors().stream().map(selector -> selector.className()).toList());
        assertEquals(List.of("()V", "(I)I"),
                plan.selectors().stream().map(selector -> selector.descriptor()).toList());
        assertEquals(CollectionBudget.defaults(), plan.budget());
    }

    @Test
    void rejectsUnknownDuplicateAndMoreThanFiftyMethods() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.TargetTest", "caseUnderTest", "()V", 0)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, request(List.of("fixture.Missing#run()V"))));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, request(List.of(
                        "fixture.TargetTest#caseUnderTest()V",
                        "fixture.TargetTest#caseUnderTest()V"))));

        List<MethodCatalogEntry> entries = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> entry("fixture.TargetTest",
                        index == 0 ? "caseUnderTest" : "m" + index, "()V", index))
                .toList();
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(entries), request(entries.stream().map(MethodCatalogEntry::methodKey).toList())));
    }

    @Test
    void rejectsBlankOrOversizedRationaleAsPlanCompilationFailure() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.TargetTest", "caseUnderTest", "()V", 0)));
        List<String> keys = List.of("fixture.TargetTest#caseUnderTest()V");

        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(
                        new PlanId("blank"), keys, " ", CollectionBudget.defaults(), NOW)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(
                        new PlanId("oversized"), keys, "x".repeat(4_097),
                        CollectionBudget.defaults(), NOW)));
    }

    @Test
    void validatesOptionalScopeAgainstCatalogAndSelectedMethods() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.Algorithm", "solve", "()V", 1)));
        String target = "fixture.TargetTest#caseUnderTest()V";
        String scope = "fixture.Algorithm#solve()V";

        CodePathCollectionPlan plan = new CodePathPlanCompiler().compile(catalog,
                new CodePathPlanRequest(new PlanId("scope-plan"), List.of(target, scope),
                        Optional.of(scope), "locate repeated paths",
                        CollectionBudget.defaults(), NOW));

        assertEquals(Optional.of(scope), plan.scopeMethodKey());
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(new PlanId("not-selected"), List.of(target),
                        Optional.of(scope), "invalid scope", CollectionBudget.defaults(), NOW)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(new PlanId("unknown"), List.of(target),
                        Optional.of("fixture.Missing#run()V"), "invalid scope",
                        CollectionBudget.defaults(), NOW)));
    }

    private CodePathPlanRequest request(List<String> keys) {
        return new CodePathPlanRequest(
                new PlanId("plan-1"), keys, "定位求解路径", CollectionBudget.defaults(), NOW);
    }

    private MethodCatalog catalog(List<MethodCatalogEntry> entries) {
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.TargetTest", "caseUnderTest"), entries, List.of(), List.of(),
                SnapshotCompleteness.COMPLETE, entries.size(), 0, NOW);
    }

    private MethodCatalogEntry entry(String className, String methodName, String descriptor, int distance) {
        String key = className + "#" + methodName + descriptor;
        return new MethodCatalogEntry(key, new SourceAnchor(
                className, methodName, descriptor, "src/test/java/fixture/TargetTest.java",
                1, 1), distance, distance == 0);
    }
}
