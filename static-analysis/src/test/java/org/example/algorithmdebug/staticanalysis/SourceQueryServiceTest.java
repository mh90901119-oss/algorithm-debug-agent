package org.example.algorithmdebug.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CallResolutionKind;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryCompleteness;
import org.example.algorithmdebug.contracts.investigation.SourceQueryErrorCode;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryMode;
import org.example.algorithmdebug.contracts.investigation.SourceQueryRequest;
import org.example.algorithmdebug.contracts.investigation.SourceQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceQueryServiceTest {
    @TempDir Path moduleRoot;

    private MethodCatalogEntry target;
    private MethodCatalogEntry alpha;
    private MethodCatalogEntry beta;
    private MethodCatalogEntry goal;
    private SourceQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        target = SourceQueryTestFixtures.target();
        alpha = SourceQueryTestFixtures.entry("Alpha", "run", 2, 4, 1, false);
        beta = SourceQueryTestFixtures.entry("Beta", "run", 2, 4, 1, false);
        goal = SourceQueryTestFixtures.entry("Goal", "finish", 2, 4, 2, false);
        for (MethodCatalogEntry entry : List.of(target, alpha, beta, goal)) {
            Path source = moduleRoot.resolve(entry.sourceAnchor().sourceRelativePath());
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package fixture;\nclass "
                    + simpleName(entry.sourceAnchor().className())
                    + " {\n  void " + entry.sourceAnchor().methodName() + "() {}\n}\n",
                    StandardCharsets.UTF_8);
        }
        service = new SourceQueryService(
                new BoundedSourceWindowReader(), new ReachablePathFinder(),
                Clock.fixed(SourceQueryTestFixtures.NOW, ZoneOffset.UTC));
    }

    @Test
    void methodReturnsExactEntryAndBoundedSourceWindow() {
        SourceQueryResult result = service.query(
                catalog(SnapshotCompleteness.COMPLETE), moduleRoot,
                request(SourceQueryMode.METHOD, Optional.of(alpha.methodKey()),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        SourceQueryBudget.defaults()));

        assertEquals(List.of(alpha), result.methods());
        assertEquals(1, result.sourceWindows().size());
        assertEquals(alpha.sourceAnchor(), result.sourceWindows().getFirst().anchor());
        assertEquals(SourceQueryCompleteness.COMPLETE, result.completeness());
        assertFalse(result.truncated());
    }

    @Test
    void callersAndCalleesAreStableAndPreserveResolutionKind() {
        MethodCatalog catalog = catalog(SnapshotCompleteness.COMPLETE);

        SourceQueryResult callers = service.query(catalog, moduleRoot,
                request(SourceQueryMode.CALLERS, Optional.of(goal.methodKey()),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        SourceQueryBudget.defaults()));
        SourceQueryResult callees = service.query(catalog, moduleRoot,
                request(SourceQueryMode.CALLEES, Optional.of(target.methodKey()),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        SourceQueryBudget.defaults()));

        assertEquals(List.of(alpha, beta), callers.methods());
        assertEquals(List.of(
                SourceQueryTestFixtures.edge(alpha, goal, 4, CallResolutionKind.POLYMORPHIC_CANDIDATE),
                SourceQueryTestFixtures.edge(beta, goal, 4, CallResolutionKind.DIRECT)), callers.edges());
        assertEquals(List.of(alpha, beta), callees.methods());
        assertEquals(List.of(CallResolutionKind.DIRECT, CallResolutionKind.DIRECT),
                callees.edges().stream().map(edge -> edge.resolutionKind()).toList());
    }

    @Test
    void reachablePathUsesStableBoundedBreadthFirstTraversal() {
        SourceQueryResult result = service.query(
                catalog(SnapshotCompleteness.COMPLETE), moduleRoot,
                request(SourceQueryMode.REACHABLE_PATH, Optional.of(target.methodKey()),
                        Optional.of(goal.methodKey()), Optional.empty(), Optional.empty(),
                        SourceQueryBudget.defaults()));

        assertEquals(List.of(
                List.of(target.methodKey(), alpha.methodKey(), goal.methodKey()),
                List.of(target.methodKey(), beta.methodKey(), goal.methodKey())), result.paths());
        assertEquals(List.of(target, alpha, goal, beta), result.methods());
    }

    @Test
    void sourceWindowUsesTheTypedAnchor() {
        SourceQueryResult result = service.query(
                catalog(SnapshotCompleteness.COMPLETE), moduleRoot,
                request(SourceQueryMode.SOURCE_WINDOW, Optional.empty(), Optional.empty(),
                        Optional.of(beta.sourceAnchor()), Optional.empty(),
                        SourceQueryBudget.defaults()));

        assertTrue(result.methods().isEmpty());
        assertEquals(beta.sourceAnchor(), result.sourceWindows().getFirst().anchor());
        assertTrue(result.sourceWindows().getFirst().text().contains("class Beta"));
    }

    @Test
    void symbolSearchReturnsStablePrefixAndMarksBudgetTruncation() {
        SourceQueryBudget oneMethod = new SourceQueryBudget(1, 10, 3, 3, 20, 4_096);

        SourceQueryResult result = service.query(
                catalog(SnapshotCompleteness.COMPLETE), moduleRoot,
                request(SourceQueryMode.SEARCH_SYMBOL, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of("fixture"), oneMethod));

        assertEquals(List.of(alpha), result.methods());
        assertTrue(result.truncated());
        assertEquals(SourceQueryCompleteness.PARTIAL, result.completeness());
        assertEquals(List.of(SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED.name()),
                result.limitations());
    }

    @Test
    void incompleteCatalogCanOnlyProducePartialStaticEvidence() {
        SourceQueryResult result = service.query(
                catalog(SnapshotCompleteness.INCOMPLETE), moduleRoot,
                request(SourceQueryMode.CALLEES, Optional.of(target.methodKey()),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        SourceQueryBudget.defaults()));

        assertEquals(SourceQueryCompleteness.PARTIAL, result.completeness());
        assertEquals(List.of(SourceQueryErrorCode.SOURCE_QUERY_CATALOG_INCOMPLETE.name()),
                result.limitations());
    }

    @Test
    void repeatedQueryIsDeterministicExceptForNoFields() {
        SourceQueryRequest request = request(
                SourceQueryMode.CALLEES, Optional.of(target.methodKey()),
                Optional.empty(), Optional.empty(), Optional.empty(), SourceQueryBudget.defaults());

        SourceQueryResult first = service.query(catalog(SnapshotCompleteness.COMPLETE), moduleRoot, request);
        SourceQueryResult second = service.query(catalog(SnapshotCompleteness.COMPLETE), moduleRoot, request);

        assertEquals(first, second);
    }

    private MethodCatalog catalog(SnapshotCompleteness completeness) {
        return SourceQueryTestFixtures.catalog(
                List.of(goal, beta, target, alpha),
                List.of(
                        SourceQueryTestFixtures.edge(beta, goal, 4, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(target, beta, 3, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(alpha, goal, 4,
                                CallResolutionKind.POLYMORPHIC_CANDIDATE),
                        SourceQueryTestFixtures.edge(target, alpha, 2, CallResolutionKind.DIRECT)),
                completeness);
    }

    private static SourceQueryRequest request(
            SourceQueryMode mode,
            Optional<String> methodKey,
            Optional<String> targetMethodKey,
            Optional<org.example.algorithmdebug.contracts.SourceAnchor> sourceAnchor,
            Optional<String> symbol,
            SourceQueryBudget budget) {
        return new SourceQueryRequest(
                SchemaVersions.SOURCE_QUERY_REQUEST,
                new SourceQueryId("query-1"),
                SourceQueryTestFixtures.CASE_ID,
                SourceQueryTestFixtures.ANALYSIS_ID,
                SourceQueryTestFixtures.CATALOG_ARTIFACT,
                mode,
                methodKey,
                targetMethodKey,
                sourceAnchor,
                symbol,
                budget,
                SourceQueryTestFixtures.NOW);
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }
}
