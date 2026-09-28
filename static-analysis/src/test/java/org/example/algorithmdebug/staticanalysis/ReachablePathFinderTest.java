package org.example.algorithmdebug.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.example.algorithmdebug.contracts.CallResolutionKind;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryErrorCode;
import org.junit.jupiter.api.Test;

class ReachablePathFinderTest {
    @Test
    void handlesCyclesAndReturnsStablePathsInBreadthFirstOrder() {
        MethodCatalogEntry target = SourceQueryTestFixtures.target();
        MethodCatalogEntry alpha = SourceQueryTestFixtures.entry("Alpha", "run", 2, 4, 1, false);
        MethodCatalogEntry beta = SourceQueryTestFixtures.entry("Beta", "run", 2, 4, 1, false);
        MethodCatalogEntry goal = SourceQueryTestFixtures.entry("Goal", "finish", 2, 4, 2, false);
        MethodCatalog catalog = SourceQueryTestFixtures.catalog(
                List.of(goal, beta, target, alpha),
                List.of(
                        SourceQueryTestFixtures.edge(alpha, target, 3, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(beta, goal, 3, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(target, beta, 3, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(alpha, goal, 4, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(target, alpha, 2, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(target, alpha, 5, CallResolutionKind.DIRECT)),
                SnapshotCompleteness.COMPLETE);
        ReachablePathFinder finder = new ReachablePathFinder();

        ReachablePathFinder.Result result = finder.find(
                MethodCatalogIndex.from(catalog), target.methodKey(), goal.methodKey(),
                SourceQueryBudget.defaults());

        assertEquals(List.of(
                List.of(target.methodKey(), alpha.methodKey(), goal.methodKey()),
                List.of(target.methodKey(), beta.methodKey(), goal.methodKey())), result.paths());
        assertTrue(result.limitations().isEmpty());
    }

    @Test
    void honorsPathAndDepthBudgetsWithStablePartialPrefix() {
        MethodCatalogEntry target = SourceQueryTestFixtures.target();
        MethodCatalogEntry alpha = SourceQueryTestFixtures.entry("Alpha", "run", 2, 4, 1, false);
        MethodCatalogEntry beta = SourceQueryTestFixtures.entry("Beta", "run", 2, 4, 1, false);
        MethodCatalogEntry goal = SourceQueryTestFixtures.entry("Goal", "finish", 2, 4, 2, false);
        MethodCatalog catalog = SourceQueryTestFixtures.catalog(
                List.of(target, alpha, beta, goal),
                List.of(
                        SourceQueryTestFixtures.edge(target, alpha, 2, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(target, beta, 3, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(alpha, goal, 4, CallResolutionKind.DIRECT),
                        SourceQueryTestFixtures.edge(beta, goal, 4, CallResolutionKind.DIRECT)),
                SnapshotCompleteness.COMPLETE);
        SourceQueryBudget onePath = new SourceQueryBudget(10, 10, 2, 1, 20, 4_096);

        ReachablePathFinder.Result result = new ReachablePathFinder().find(
                MethodCatalogIndex.from(catalog), target.methodKey(), goal.methodKey(), onePath);

        assertEquals(
                List.of(List.of(target.methodKey(), alpha.methodKey(), goal.methodKey())),
                result.paths());
        assertTrue(result.truncated());
        assertEquals(
                List.of(SourceQueryErrorCode.SOURCE_QUERY_BUDGET_EXCEEDED.name()),
                result.limitations());
    }
}
