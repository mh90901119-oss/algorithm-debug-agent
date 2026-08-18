package org.example.algorithmdebug.contracts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class MethodCatalogTest {
    private static final CaseId CASE = new CaseId("case-1");
    private static final ContextId CONTEXT = new ContextId("context-1");
    private static final AnalysisId ANALYSIS = new AnalysisId("analysis-1");
    private static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "caseUnderTest");
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    @Test void acceptsBoundedCatalogAndDefensivelyCopiesLists() {
        List<MethodCatalogEntry> entries = new ArrayList<>(List.of(target(), service()));
        MethodCatalog catalog = catalog(entries, List.of(new MethodCallEdge(target().methodKey(), service().methodKey(), 12)), 2, 1);
        entries.clear(); assertEquals(2, catalog.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
    }
    @Test void rejectsMissingEdgesDuplicateMethodsAndBadCounts() {
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(target()), List.of(new MethodCallEdge(target().methodKey(), service().methodKey(), 12)), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(target(), target()), List.of(), 2, 0));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(target(), service()), List.of(), 1, 0));
    }
    @Test void requiresOneMatchingTargetAndExactSelectorDescriptor() {
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(service()), List.of(), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MethodSelector("fixture.Service#run()", "fixture.Service", "run", "()"));
    }
    @Test void rejectsV1AndOversizedWarnings() {
        assertThrows(IllegalArgumentException.class, () -> new MethodCatalog("1.0", CASE, CONTEXT, ANALYSIS, TARGET, List.of(target()), List.of(), List.of(), SnapshotCompleteness.COMPLETE, 1, 0, NOW));
        assertThrows(IllegalArgumentException.class, () -> new MethodCatalog(SchemaVersions.METHOD_CATALOG, CASE, CONTEXT, ANALYSIS, TARGET, List.of(target()), List.of(), List.of("x".repeat(2_049)), SnapshotCompleteness.COMPLETE, 1, 0, NOW));
    }
    private static MethodCatalog catalog(List<MethodCatalogEntry> entries, List<MethodCallEdge> edges, int methods, int edgeCount) {
        return new MethodCatalog(SchemaVersions.METHOD_CATALOG, CASE, CONTEXT, ANALYSIS, TARGET, entries, edges, List.of(), SnapshotCompleteness.COMPLETE, methods, edgeCount, NOW);
    }
    private static MethodCatalogEntry target() { return entry("fixture.TargetTest", "caseUnderTest", 0, true); }
    private static MethodCatalogEntry service() { return entry("fixture.Service", "solve", 1, false); }
    private static MethodCatalogEntry entry(String type, String method, int distance, boolean target) {
        return new MethodCatalogEntry(type + "#" + method + "()V", new SourceAnchor(type, method, "()V", "src/main/java/" + type.replace('.', '/') + ".java", 1, 3, "b".repeat(64)), distance, target);
    }
}
