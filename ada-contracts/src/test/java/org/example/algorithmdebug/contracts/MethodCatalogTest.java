package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MethodCatalogTest {

    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "caseUnderTest");
    private static final Instant CREATED_AT = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void acceptsBoundedCatalogAndDefensivelyCopiesLists() {
        List<MethodCatalogEntry> entries = new ArrayList<>(List.of(target(), service()));
        List<MethodCallEdge> edges = new ArrayList<>(List.of(
                new MethodCallEdge(target().methodKey(), service().methodKey(), 12)));
        MethodCatalog catalog = catalog(
                entries, edges, List.of("unresolved external call"),
                SnapshotCompleteness.INCOMPLETE, 3, 2);

        entries.clear();
        edges.clear();

        assertEquals(2, catalog.entries().size());
        assertEquals(1, catalog.edges().size());
        assertEquals("a".repeat(64), catalog.sourceFingerprintSha256());
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
    }

    @Test
    void rejectsEdgesWhoseMethodsAreMissingFromCatalog() {
        MethodCallEdge missing = new MethodCallEdge(
                target().methodKey(), "fixture.Missing#solve()", 12);

        assertThrows(IllegalArgumentException.class, () -> catalog(
                List.of(target()), List.of(missing), List.of(),
                SnapshotCompleteness.COMPLETE, 1, 1));
    }

    @Test
    void requiresExactlyOneMatchingTargetMethod() {
        MethodCatalogEntry secondTarget = new MethodCatalogEntry(
                "fixture.TargetTest#caseUnderTest(I)V",
                new SourceAnchor(
                        "fixture.TargetTest", "caseUnderTest", "(I)V",
                        "src/test/java/fixture/TargetTest.java", 20, 22, "b".repeat(64)),
                0,
                true);

        assertThrows(IllegalArgumentException.class, () -> catalog(
                List.of(service()), List.of(), List.of(),
                SnapshotCompleteness.COMPLETE, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> catalog(
                List.of(target(), secondTarget), List.of(), List.of(),
                SnapshotCompleteness.COMPLETE, 2, 0));
    }

    @Test
    void rejectsDuplicateMethodsAndInvalidCompletenessCounts() {
        assertThrows(IllegalArgumentException.class, () -> catalog(
                List.of(target(), target()), List.of(), List.of(),
                SnapshotCompleteness.COMPLETE, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> catalog(
                List.of(target(), service()), List.of(), List.of(),
                SnapshotCompleteness.INCOMPLETE, 1, 0));
    }

    @Test
    void packageCensusIsBoundedSortedAndClosesCompleteMethodCount() {
        assertThrows(IllegalArgumentException.class, () -> new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, CASE_ID, CONTEXT_ID, ANALYSIS_ID, TARGET,
                "a".repeat(64), List.of(target()), List.of(), List.of(),
                List.of(new PackageCensusEntry("z.last", 1),
                        new PackageCensusEntry("a.first", 1)),
                SnapshotCompleteness.COMPLETE, SnapshotCompleteness.COMPLETE,
                2, 0, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, CASE_ID, CONTEXT_ID, ANALYSIS_ID, TARGET,
                "a".repeat(64), List.of(target()), List.of(), List.of(),
                List.of(new PackageCensusEntry("fixture", 2)),
                SnapshotCompleteness.COMPLETE, SnapshotCompleteness.COMPLETE,
                1, 0, CREATED_AT));
    }

    @Test
    void sourceAnchorRejectsUnsafePathAndInvalidLineRange() {
        assertThrows(IllegalArgumentException.class, () -> new SourceAnchor(
                "fixture.TargetTest", "caseUnderTest", "()V", "../TargetTest.java",
                10, 12, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new SourceAnchor(
                "fixture.TargetTest", "caseUnderTest", "()V",
                "src/test/java/fixture/TargetTest.java", 12, 10, "a".repeat(64)));
    }

    @Test
    void methodEntryRequiresExactJvmDescriptorIdentity() {
        SourceAnchor anchor = new SourceAnchor(
                "fixture.TargetTest", "caseUnderTest", "()V",
                "src/test/java/fixture/TargetTest.java", 10, 14, "b".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> new MethodCatalogEntry(
                "fixture.TargetTest#caseUnderTest(I)V", anchor, 0, true));
    }

    @Test
    void sourceAnchorSupportsJvmConstructorName() {
        SourceAnchor constructor = new SourceAnchor(
                "fixture.Service", "<init>", "()V",
                "src/main/java/fixture/Service.java", 3, 4, "b".repeat(64));

        assertEquals("<init>", constructor.methodName());
    }

    @Test
    void sourceAnchorRejectsMalformedJvmMethodDescriptors() {
        for (String descriptor : List.of(
                "()", "(int)V", "(V)V", "(Ljava/lang/String)V", "I)V", "([V)V",
                "(Lbad-name;)V")) {
            assertThrows(IllegalArgumentException.class, () -> new SourceAnchor(
                    "fixture.Service", "run", descriptor,
                    "src/main/java/fixture/Service.java", 3, 4, "b".repeat(64)),
                    descriptor);
        }
        assertThrows(IllegalArgumentException.class, () -> new SourceAnchor(
                "fixture.Service", "<init>", "()I",
                "src/main/java/fixture/Service.java", 3, 4, "b".repeat(64)));
    }

    @Test
    void methodSelectorRejectsMalformedJvmMethodDescriptor() {
        assertThrows(IllegalArgumentException.class, () -> new MethodSelector(
                "fixture.Service#run()", "fixture.Service", "run", "()", "b".repeat(64)));
    }

    @Test
    void rejectsUnsupportedVersionAndOversizedWarnings() {
        assertThrows(IllegalArgumentException.class, () -> new MethodCatalog(
                "2.0", CASE_ID, CONTEXT_ID, ANALYSIS_ID, TARGET, "a".repeat(64),
                List.of(target()), List.of(), List.of(),
                List.of(new PackageCensusEntry("fixture", 1)),
                SnapshotCompleteness.COMPLETE, SnapshotCompleteness.COMPLETE,
                1, 0, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> catalog(
                List.of(target()), List.of(), List.of("x".repeat(2_049)),
                SnapshotCompleteness.COMPLETE, 1, 0));
    }

    private static MethodCatalog catalog(
            List<MethodCatalogEntry> entries,
            List<MethodCallEdge> edges,
            List<String> warnings,
            SnapshotCompleteness completeness,
            int discoveredMethods,
            int discoveredEdges) {
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                CASE_ID,
                CONTEXT_ID,
                ANALYSIS_ID,
                TARGET,
                "A".repeat(64),
                entries,
                edges,
                warnings,
                List.of(new PackageCensusEntry("fixture",
                        completeness == SnapshotCompleteness.COMPLETE
                                ? discoveredMethods : entries.size())),
                completeness,
                completeness,
                discoveredMethods,
                discoveredEdges,
                CREATED_AT);
    }

    private static MethodCatalogEntry target() {
        return new MethodCatalogEntry(
                "fixture.TargetTest#caseUnderTest()V",
                new SourceAnchor(
                        "fixture.TargetTest", "caseUnderTest", "()V",
                        "src/test/java/fixture/TargetTest.java", 10, 14, "b".repeat(64)),
                0,
                true);
    }

    private static MethodCatalogEntry service() {
        return new MethodCatalogEntry(
                "fixture.Service#solve()V",
                new SourceAnchor(
                        "fixture.Service", "solve", "()V",
                        "src/main/java/fixture/Service.java", 5, 8, "c".repeat(64)),
                1,
                false);
    }
}
