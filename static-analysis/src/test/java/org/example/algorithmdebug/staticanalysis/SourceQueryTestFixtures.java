package org.example.algorithmdebug.staticanalysis;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CallResolutionKind;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.MethodCallEdge;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;

final class SourceQueryTestFixtures {
    static final CaseId CASE_ID = new CaseId("case-1");
    static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "caseUnderTest");
    static final Instant NOW = Instant.parse("2026-09-28T03:00:00Z");
    static final ArtifactReference CATALOG_ARTIFACT = new ArtifactReference(
            "catalog-1", "METHOD_CATALOG", "analyses/analysis-1/method-catalog.json",
            "application/json", "a".repeat(64), 128);

    private SourceQueryTestFixtures() {
    }

    static MethodCatalogEntry target() {
        return entry("TargetTest", "caseUnderTest", 1, 6, 0, true);
    }

    static MethodCatalogEntry entry(
            String className,
            String methodName,
            int startLine,
            int endLine,
            int distance,
            boolean target) {
        String qualifiedName = "fixture." + className;
        SourceAnchor anchor = new SourceAnchor(
                qualifiedName,
                methodName,
                "()V",
                "src/main/java/fixture/" + className + ".java",
                startLine,
                endLine);
        return new MethodCatalogEntry(
                qualifiedName + "#" + methodName + "()V", anchor, distance, target);
    }

    static MethodCallEdge edge(
            MethodCatalogEntry caller,
            MethodCatalogEntry callee,
            int line,
            CallResolutionKind kind) {
        return new MethodCallEdge(
                caller.methodKey(), callee.methodKey(), line, kind);
    }

    static MethodCatalog catalog(
            List<MethodCatalogEntry> entries,
            List<MethodCallEdge> edges,
            SnapshotCompleteness completeness) {
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                CASE_ID,
                ANALYSIS_ID,
                TARGET,
                entries,
                edges,
                List.of(),
                completeness,
                entries.size(),
                edges.size(),
                NOW);
    }
}
