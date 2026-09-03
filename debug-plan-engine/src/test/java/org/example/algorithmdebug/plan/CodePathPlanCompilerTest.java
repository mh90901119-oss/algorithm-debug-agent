package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CodePathProjectionSource;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.InvestigationIntent;
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
                plan.methodSelections().stream()
                        .map(selection -> selection.selector().className()).toList());
        assertEquals(List.of("()V", "(I)I"),
                plan.methodSelections().stream()
                        .map(selection -> selection.selector().descriptor()).toList());
        assertEquals(CollectionBudget.defaults(), plan.budget());
        assertEquals("Which methods executed?", plan.intent().questionToAnswer());
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
    void rejectsBlankOrOversizedRationaleAtTheRequestBoundary() {
        List<String> keys = List.of("fixture.TargetTest#caseUnderTest()V");

        assertThrows(IllegalArgumentException.class, () -> new CodePathPlanRequest(
                new PlanId("blank"), methods(keys.toArray(String[]::new)), Optional.empty(),
                " ", intent(), CollectionBudget.defaults(), NOW));
        assertThrows(IllegalArgumentException.class, () -> new CodePathPlanRequest(
                new PlanId("oversized"), methods(keys.toArray(String[]::new)), Optional.empty(),
                "x".repeat(4_097), intent(), CollectionBudget.defaults(), NOW));
    }

    @Test
    void validatesOptionalScopeAgainstCatalogAndSelectedMethods() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.Algorithm", "solve", "()V", 1)));
        String target = "fixture.TargetTest#caseUnderTest()V";
        String scope = "fixture.Algorithm#solve()V";

        CodePathCollectionPlan plan = new CodePathPlanCompiler().compile(catalog,
                new CodePathPlanRequest(new PlanId("scope-plan"), methods(target, scope),
                        Optional.of(scope), "locate repeated paths",
                        intent(), CollectionBudget.defaults(), NOW));

        assertEquals(Optional.of(scope), plan.scopeMethodKey());
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(new PlanId("not-selected"), methods(target),
                        Optional.of(scope), "invalid scope", intent(),
                        CollectionBudget.defaults(), NOW)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(new PlanId("unknown"), methods(target),
                        Optional.of("fixture.Missing#run()V"), "invalid scope",
                        intent(), CollectionBudget.defaults(), NOW)));
    }

    @Test
    void compilesArgumentAndReturnPathsIntoStructuredScalarProjections() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.internal.Service", "solve", "(Lfixture/Context;I)Lfixture/Result;", 1)));
        String methodKey = "fixture.internal.Service#solve(Lfixture/Context;I)Lfixture/Result;";
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("projection-plan"),
                List.of(new CodePathMethodRequest(methodKey, List.of(
                        new CodePathProjectionRequest("waferId", "arg[0].wafer.id", true),
                        new CodePathProjectionRequest("attempt", "arg[1]", false),
                        new CodePathProjectionRequest("selectedChamber", "return.chamber", false)))),
                Optional.empty(), "observe identities", intent(), CollectionBudget.defaults(), NOW);

        CodePathCollectionPlan plan = new CodePathPlanCompiler().compile(catalog, request);

        var projections = plan.methodSelections().getFirst().projections();
        assertEquals(3, projections.size());
        assertEquals(CodePathProjectionSource.ARGUMENT, projections.get(0).source());
        assertEquals(0, projections.get(0).argumentIndex().orElseThrow());
        assertEquals(List.of("wafer", "id"), projections.get(0).fieldPath());
        assertEquals(CodePathProjectionSource.RETURN, projections.get(2).source());
        assertEquals(List.of("chamber"), projections.get(2).fieldPath());
    }

    @Test
    void rejectsProjectionPathsThatDoNotMatchTheMethodDescriptor() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.internal.Service", "solve", "(I)V", 1)));
        String methodKey = "fixture.internal.Service#solve(I)V";

        assertProjectionRejected(catalog, methodKey, "arg[1]");
        assertProjectionRejected(catalog, methodKey, "return.value");
        assertProjectionRejected(catalog, methodKey, "arg[0].items[0]");
        assertProjectionRejected(catalog, methodKey, "arg[0].getId()");
    }

    @Test
    void rejectsDuplicateProjectionNamesWithinOneMethod() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.internal.Service", "solve", "(I)V", 1)));
        String methodKey = "fixture.internal.Service#solve(I)V";
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("duplicate-projection"),
                List.of(new CodePathMethodRequest(methodKey, List.of(
                        new CodePathProjectionRequest("value", "arg[0]", true),
                        new CodePathProjectionRequest("value", "arg[0]", false)))),
                Optional.empty(), "observe value", intent(), CollectionBudget.defaults(), NOW);

        assertThrows(PlanCompilationException.class,
                () -> new CodePathPlanCompiler().compile(catalog, request));
    }

    private CodePathPlanRequest request(List<String> keys) {
        return new CodePathPlanRequest(
                new PlanId("plan-1"), methods(keys.toArray(String[]::new)), Optional.empty(),
                "Locate the runtime path", intent(), CollectionBudget.defaults(), NOW);
    }

    private List<CodePathMethodRequest> methods(String... keys) {
        return java.util.Arrays.stream(keys)
                .map(key -> new CodePathMethodRequest(key, List.of())).toList();
    }

    private InvestigationIntent intent() {
        return new InvestigationIntent(
                "Which methods executed?", "One candidate path executed",
                List.of(), List.of("Observed method entries"));
    }

    private void assertProjectionRejected(MethodCatalog catalog, String methodKey, String path) {
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("invalid-projection"),
                List.of(new CodePathMethodRequest(methodKey, List.of(
                        new CodePathProjectionRequest("value", path, true)))),
                Optional.empty(), "observe value", intent(), CollectionBudget.defaults(), NOW);
        assertThrows(PlanCompilationException.class,
                () -> new CodePathPlanCompiler().compile(catalog, request));
    }

    private MethodCatalog catalog(List<MethodCatalogEntry> entries) {
        String targetKey = "fixture.TargetTest#caseUnderTest()V";
        List<MethodCatalogEntry> completeEntries = entries.stream()
                .anyMatch(entry -> entry.methodKey().equals(targetKey))
                ? entries
                : java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(entry(
                                "fixture.TargetTest", "caseUnderTest", "()V", 0)),
                        entries.stream()).toList();
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.TargetTest", "caseUnderTest"), completeEntries, List.of(), List.of(),
                SnapshotCompleteness.COMPLETE, completeEntries.size(), 0, NOW);
    }

    private MethodCatalogEntry entry(String className, String methodName, String descriptor, int distance) {
        String key = className + "#" + methodName + descriptor;
        return new MethodCatalogEntry(key, new SourceAnchor(
                className, methodName, descriptor, "src/test/java/fixture/TargetTest.java",
                1, 1), distance, distance == 0);
    }
}
