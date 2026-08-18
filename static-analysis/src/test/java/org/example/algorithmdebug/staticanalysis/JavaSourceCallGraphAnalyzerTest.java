package org.example.algorithmdebug.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSourceCallGraphAnalyzerTest {

    private static final String HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsAtTargetMethodAndRecordsResolvedOverload() throws IOException {
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                class TargetTest {
                    void caseUnderTest() { new Service().solve(1); }
                    void unreachable() { new Service().unused(); }
                }
                """);
        write("src/main/java/fixture/Service.java", """
                package fixture;
                class Service {
                    int solve(int value) { return helper(value); }
                    int solve(String value) { return value.length(); }
                    int helper(int value) { return value + 1; }
                    void unused() { }
                }
                """);

        MethodCatalog catalog = analyzer().analyze(request(StaticAnalysisBudget.defaults()));

        assertEquals(4, catalog.entries().size());
        assertTrue(catalog.entries().stream().anyMatch(entry ->
                entry.methodKey().contains("Service#<init>()V")));
        assertTrue(catalog.entries().stream().anyMatch(entry ->
                entry.methodKey().contains("Service#solve(I)I")));
        assertFalse(catalog.entries().stream().anyMatch(entry ->
                entry.methodKey().contains("Service#solve(Ljava/lang/String;)I")));
        assertFalse(catalog.entries().stream().anyMatch(entry ->
                entry.methodKey().contains("unreachable")));
        assertEquals(3, catalog.edges().size());
        assertEquals(SnapshotCompleteness.COMPLETE, catalog.completeness());
    }

    @Test
    void truncatesDeterministicallyAtMethodBudget() throws IOException {
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                class TargetTest { void caseUnderTest() { new Service().first(); } }
                """);
        write("src/main/java/fixture/Service.java", """
                package fixture;
                class Service {
                    void first() { second(); }
                    void second() { third(); }
                    void third() { }
                }
                """);
        StaticAnalysisBudget budget = budget(10, 100_000, 2, 10, 5_000);

        MethodCatalog first = analyzer().analyze(request(budget));
        MethodCatalog second = analyzer().analyze(request(budget));

        assertEquals(SnapshotCompleteness.INCOMPLETE, first.completeness());
        assertEquals(1, first.entries().size());
        assertEquals(3, first.discoveredMethodCount());
        assertEquals(first.entries(), second.entries());
        assertTrue(first.warnings().stream().anyMatch(value -> value.contains("method budget")));
    }

    @Test
    void rejectsMissingOrAmbiguousTargetMethod() throws IOException {
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                class TargetTest {
                    void caseUnderTest(int value) { }
                    void caseUnderTest(String value) { }
                }
                """);

        assertThrows(StaticAnalysisException.class,
                () -> analyzer().analyze(request(StaticAnalysisBudget.defaults())));
    }

    @Test
    void preservesBoundedSyntaxWarningForUnresolvedExternalInvocation() throws IOException {
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                class TargetTest {
                    void caseUnderTest() { MissingDependency.execute(); }
                }
                """);

        MethodCatalog catalog = analyzer().analyze(request(StaticAnalysisBudget.defaults()));

        assertEquals(SnapshotCompleteness.INCOMPLETE, catalog.completeness());
        assertTrue(catalog.warnings().stream().anyMatch(warning ->
                warning.contains("syntax-level unresolved invocation")
                        && warning.contains("MissingDependency.execute()")));
        assertTrue(catalog.warnings().stream().allMatch(warning -> warning.length() <= 2_048));
    }

    @Test
    void recordsConstructorCallsAndInternalClassBinaryNames() throws IOException {
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                class TargetTest {
                    void caseUnderTest() { new Container.Worker().run(); }
                }
                """);
        write("src/main/java/fixture/Container.java", """
                package fixture;
                class Container {
                    static class Worker {
                        Worker() { }
                        void run() { }
                    }
                }
                """);

        MethodCatalog catalog = analyzer().analyze(request(StaticAnalysisBudget.defaults()));

        assertTrue(catalog.entries().stream().anyMatch(entry ->
                entry.methodKey().equals("fixture.Container$Worker#<init>()V")));
        assertTrue(catalog.entries().stream().anyMatch(entry ->
                entry.methodKey().equals("fixture.Container$Worker#run()V")));
        assertTrue(catalog.edges().stream().anyMatch(edge ->
                edge.calleeKey().equals("fixture.Container$Worker#<init>()V")));
    }

    @Test
    void unrelatedPackagesDoNotPolluteReachableMethodEntries() throws IOException {
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                class TargetTest { void caseUnderTest() { } }
                """);
        write("src/main/java/unreachable/Unused.java", """
                package unreachable;
                class Unused { void first() { } void second() { } }
                """);

        MethodCatalog catalog = analyzer().analyze(request(StaticAnalysisBudget.defaults()));

        assertTrue(catalog.entries().stream().noneMatch(entry ->
                entry.sourceAnchor().className().startsWith("unreachable.")));
        assertEquals(5, catalog.discoveredMethodCount());
    }

    @Test
    void compilerErrorWithoutUnresolvedInvocationMakesCatalogIncomplete() throws IOException {
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                class TargetTest {
                    MissingType field;
                    void caseUnderTest() { }
                }
                """);

        MethodCatalog catalog = analyzer().analyze(request(StaticAnalysisBudget.defaults()));

        assertEquals(SnapshotCompleteness.INCOMPLETE, catalog.completeness());
        assertTrue(catalog.warnings().stream().anyMatch(value -> value.startsWith("compiler: ")));
    }

    @Test
    void methodAndEdgeScansStopAtFirstItemBeyondBudget() throws IOException {
        StringBuilder methods = new StringBuilder();
        StringBuilder calls = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            methods.append("void m").append(index).append("() { }\n");
            calls.append("m").append(index).append("();");
        }
        write("src/test/java/fixture/TargetTest.java", "package fixture; class TargetTest {"
                + "void caseUnderTest() {" + calls + "}" + methods + "}");

        MethodCatalog methodLimited = analyzer().analyze(request(
                budget(10, 100_000, 5, 100, 5_000)));
        MethodCatalog edgeLimited = analyzer().analyze(request(
                budget(10, 100_000, 100, 5, 5_000)));

        assertEquals(6, methodLimited.discoveredMethodCount());
        assertTrue(methodLimited.warnings().stream().anyMatch(value -> value.contains("method budget")));
        assertEquals(6, edgeLimited.discoveredEdgeCount());
        assertTrue(edgeLimited.warnings().stream().anyMatch(value -> value.contains("edge budget")));
    }

    @Test
    void criticalBudgetReasonSurvivesOneThousandParseWarnings() throws IOException {
        StringBuilder unresolved = new StringBuilder();
        for (int index = 0; index < 1_100; index++) {
            unresolved.append("MissingDependency.call").append(index).append("();");
        }
        write("src/test/java/fixture/TargetTest.java", "package fixture; class TargetTest {"
                + "void caseUnderTest() {" + unresolved + "}} ");
        write("src/main/java/fixture/ZExtra.java", "package fixture; class ZExtra { }");

        MethodCatalog catalog = analyzer().analyze(request(
                budget(1, 1_000_000, 10, 10, 10_000)));

        assertEquals(1_000, catalog.warnings().size());
        assertTrue(catalog.warnings().stream().anyMatch(value -> value.contains("source budget")));
    }

    @Test
    void sourceByteBudgetStopsBeforeOversizedNextSource() throws IOException {
        String targetSource = "package fixture; class TargetTest { void caseUnderTest() { } }";
        write("src/test/java/fixture/TargetTest.java", targetSource);
        write("src/main/java/oversized/Huge.java",
                "package oversized; class Huge {" + " ".repeat(5_000) + "}");
        long targetBytes = targetSource.getBytes(StandardCharsets.UTF_8).length;

        MethodCatalog catalog = analyzer().analyze(request(
                budget(10, targetBytes + 8, 100, 100, 5_000)));

        assertEquals(SnapshotCompleteness.INCOMPLETE, catalog.completeness());
        assertTrue(catalog.warnings().stream().anyMatch(value -> value.contains("source budget")));
    }

    @Test
    void catalogByteBudgetBoundsLongKeysPathsAndManyEdges() throws IOException {
        String deepPackage = String.join(".",
                "catalogbudgetsegmentalpha", "catalogbudgetsegmentbeta",
                "catalogbudgetsegmentgamma", "catalogbudgetsegmentdelta");
        String serviceClass = deepPackage + ".LongServiceForCatalogBudget";
        StringBuilder calls = new StringBuilder();
        for (int index = 0; index < 4_000; index++) {
            calls.append("service.work();\n");
        }
        write("src/test/java/fixture/TargetTest.java", """
                package fixture;
                import %s;
                class TargetTest {
                    void caseUnderTest() {
                        LongServiceForCatalogBudget service = new LongServiceForCatalogBudget();
                        %s
                    }
                }
                """.formatted(serviceClass, calls));
        write("src/main/java/" + deepPackage.replace('.', '/')
                + "/LongServiceForCatalogBudget.java", """
                package %s;
                public class LongServiceForCatalogBudget {
                    public void work() { }
                }
                """.formatted(deepPackage));
        StaticAnalysisBudget budget = new StaticAnalysisBudget(
                10, 2L * 1024 * 1024, 100, 10_000,
                StaticAnalysisBudget.MIN_CATALOG_BYTES, 20_000);

        MethodCatalog catalog = analyzer().analyze(request(budget));
        byte[] json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsBytes(catalog);

        assertEquals(SnapshotCompleteness.INCOMPLETE, catalog.completeness());
        assertTrue(catalog.warnings().stream().anyMatch(value ->
                value.contains("catalog byte budget")));
        assertEquals(catalog.edges().size() + 1, catalog.discoveredEdgeCount());
        assertTrue(catalog.edges().size() > 1_000);
        assertTrue(catalog.entries().stream().anyMatch(entry ->
                entry.methodKey().length() > 100
                        && entry.sourceAnchor().sourceRelativePath().length() > 140));
        assertTrue(json.length <= budget.maxCatalogBytes(),
                () -> "actualBytes=" + json.length + ", budget=" + budget.maxCatalogBytes());
        assertTrue(budget.maxCatalogBytes() <= StaticAnalysisBudget.MAX_CATALOG_BYTES);
    }

    private JavaSourceCallGraphAnalyzer analyzer() {
        return new JavaSourceCallGraphAnalyzer();
    }

    private StaticAnalysisBudget budget(
            int maxFiles, long maxSourceBytes, int maxMethods, int maxEdges, long timeoutMillis) {
        return new StaticAnalysisBudget(
                maxFiles, maxSourceBytes, maxMethods, maxEdges,
                StaticAnalysisBudget.DEFAULT_CATALOG_BYTES, timeoutMillis);
    }

    private StaticAnalysisRequest request(StaticAnalysisBudget budget) {
        return new StaticAnalysisRequest(
                temporaryDirectory,
                new TargetTest("fixture.TargetTest", "caseUnderTest"),
                new CaseId("case-1"),
                new ContextId("ctx-1"),
                new AnalysisId("analysis-1"),
                budget,
                Instant.parse("2026-08-18T00:00:00Z"));
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
