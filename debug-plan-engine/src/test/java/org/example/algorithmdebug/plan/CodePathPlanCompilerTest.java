package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.PackageCensusEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;

class CodePathPlanCompilerTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void compilesStableMethodAllowlistAndPackageSuperset() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.Service", "solve", "(I)I", 1)));
        CodePathPlanRequest request = request(List.of(
                "fixture.Service#solve(I)I",
                "fixture.TargetTest#caseUnderTest()V"), 50_000);

        CodePathCollectionPlan plan = new CodePathPlanCompiler().compile(catalog, request);

        assertEquals(List.of("fixture.Service", "fixture.TargetTest"),
                plan.selectors().stream().map(selector -> selector.className()).toList());
        assertEquals(List.of("fixture"), plan.packagePrefixes());
        assertEquals("PACKAGE_SUPERSET", plan.captureScope());
        assertEquals(50_000, plan.estimatedPackageEvents());
    }

    @Test
    void rejectsUnknownMethodAndUnsafeSupersetEstimate() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.TargetTest", "caseUnderTest", "()V", 0)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, request(List.of("fixture.Missing#run()V"), 1)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, request(List.of("fixture.TargetTest#caseUnderTest()V"), 1_000_001)));
    }

    @Test
    void rejectsMoreThanTwoHundredSelectedMethods() {
        List<MethodCatalogEntry> entries = java.util.stream.IntStream.range(0, 201)
                .mapToObj(index -> entry("fixture.TargetTest", index == 0 ? "caseUnderTest" : "m" + index,
                        "()V", index))
                .toList();
        List<String> keys = entries.stream().map(MethodCatalogEntry::methodKey).toList();
        assertThrows(PlanCompilationException.class,
                () -> new CodePathPlanCompiler().compile(catalog(entries), request(keys, 10_000)));
    }

    @Test
    void replacesCallerZeroEstimateWithDeterministicPackageLowerBound() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.Service", "solve", "()V", 1)));

        CodePathCollectionPlan plan = new CodePathPlanCompiler().compile(
                catalog, request(List.of("fixture.TargetTest#caseUnderTest()V"), 0));

        assertEquals(20_000, plan.estimatedPackageEvents());
    }

    @Test
    void estimatesTheSelectedPackageBoundaryTreeWithoutIncludingSiblingPrefixes() {
        MethodCatalogEntry target = entry("com.foo.TargetTest", "caseUnderTest", "()V", 0);
        MethodCatalog catalog = new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("com.foo.TargetTest", "caseUnderTest"), HASH,
                List.of(target), List.of(), List.of(), List.of(
                        new PackageCensusEntry("com.foo", 1),
                        new PackageCensusEntry("com.foo.sub", 2),
                        new PackageCensusEntry("com.foobar", 100)),
                SnapshotCompleteness.COMPLETE, SnapshotCompleteness.COMPLETE,
                103, 0, NOW);

        CodePathCollectionPlan plan = new CodePathPlanCompiler().compile(
                catalog, request(List.of(target.methodKey()), 0));

        assertEquals(List.of("com.foo"), plan.packagePrefixes());
        assertEquals(30_000, plan.estimatedPackageEvents());
    }

    @Test
    void rejectsWhenDeterministicPackageEstimateExceedsOneMillion() {
        List<MethodCatalogEntry> entries = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> entry(
                        "fixture.TargetTest", index == 0 ? "caseUnderTest" : "m" + index,
                        "()V", index))
                .toList();

        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog(entries),
                request(List.of("fixture.TargetTest#caseUnderTest()V"), 0)));
    }

    @Test
    void rejectsCrossExactPackageSelectionInsteadOfWideningCommonPrefix() {
        MethodCatalog catalog = catalog(List.of(
                entry("fixture.TargetTest", "caseUnderTest", "()V", 0),
                entry("fixture.internal.Service", "solve", "()V", 1)));

        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, request(List.of(
                        "fixture.TargetTest#caseUnderTest()V",
                        "fixture.internal.Service#solve()V"), 0)));
    }

    @Test
    void rejectsIncompleteCensusAndUsesActualUnreachablePackageCount() {
        MethodCatalogEntry target = entry("fixture.TargetTest", "caseUnderTest", "()V", 0);
        MethodCatalog incomplete = catalog(
                List.of(target), List.of(new PackageCensusEntry("fixture", 1)),
                SnapshotCompleteness.INCOMPLETE, 2);
        MethodCatalog expensive = catalog(
                List.of(target), List.of(new PackageCensusEntry("fixture", 101)),
                SnapshotCompleteness.COMPLETE, 101);

        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                incomplete, request(List.of(target.methodKey()), 0)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                expensive, request(List.of(target.methodKey()), 0)));
    }

    @Test
    void rejectsBlankOrOversizedRationaleAsPlanCompilationFailure() {
        MethodCatalog catalog = catalog(List.of(entry(
                "fixture.TargetTest", "caseUnderTest", "()V", 0)));

        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(
                        new PlanId("blank"), List.of("fixture.TargetTest#caseUnderTest()V"), " ",
                        CollectionBudget.defaults(), 0, NOW)));
        assertThrows(PlanCompilationException.class, () -> new CodePathPlanCompiler().compile(
                catalog, new CodePathPlanRequest(
                        new PlanId("oversized"), List.of("fixture.TargetTest#caseUnderTest()V"),
                        "x".repeat(4_097), CollectionBudget.defaults(), 0, NOW)));
    }

    private CodePathPlanRequest request(List<String> keys, long estimatedEvents) {
        return new CodePathPlanRequest(
                new PlanId("plan-1"), keys, "定位求解路径", CollectionBudget.defaults(),
                estimatedEvents, NOW);
    }

    private MethodCatalog catalog(List<MethodCatalogEntry> entries) {
        Map<String, Integer> counts = new TreeMap<>();
        entries.forEach(entry -> counts.merge(packageName(
                entry.sourceAnchor().className()), 1, Integer::sum));
        List<PackageCensusEntry> census = counts.entrySet().stream()
                .map(entry -> new PackageCensusEntry(entry.getKey(), entry.getValue()))
                .toList();
        return catalog(entries, census, SnapshotCompleteness.COMPLETE, entries.size());
    }

    private MethodCatalog catalog(
            List<MethodCatalogEntry> entries,
            List<PackageCensusEntry> census,
            SnapshotCompleteness censusCompleteness,
            int discoveredMethods) {
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"), new ContextId("ctx-1"), new AnalysisId("analysis-1"),
                new TargetTest("fixture.TargetTest", "caseUnderTest"), HASH, entries, List.of(),
                List.of(), census,
                censusCompleteness == SnapshotCompleteness.COMPLETE
                        ? SnapshotCompleteness.COMPLETE : SnapshotCompleteness.INCOMPLETE,
                censusCompleteness, discoveredMethods, 0, NOW);
    }

    private static String packageName(String className) {
        return className.substring(0, className.lastIndexOf('.'));
    }

    private MethodCatalogEntry entry(String className, String methodName, String descriptor, int distance) {
        String key = className + "#" + methodName + descriptor;
        return new MethodCatalogEntry(key, new SourceAnchor(
                className, methodName, descriptor, "src/test/java/fixture/TargetTest.java",
                1, 1, HASH), distance, distance == 0);
    }
}
