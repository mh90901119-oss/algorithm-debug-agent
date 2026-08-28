package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdwpPlanCompilerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @TempDir
    Path moduleRoot;

    private SourceAnchor targetAnchor;
    private SourceAnchor serviceAnchor;

    @BeforeEach
    void createSources() throws Exception {
        targetAnchor = source(
                "src/test/java/fixture/AlgorithmTest.java",
                "fixture.AlgorithmTest", "runs", "()V", 3, 5,
                "package fixture;\nclass AlgorithmTest {\n void runs() {\n  new Algorithm().schedule();\n }\n}\n");
        serviceAnchor = source(
                "src/main/java/fixture/Algorithm.java",
                "fixture.Algorithm", "schedule", "()V", 3, 6,
                "package fixture;\nclass Algorithm {\n void schedule() {\n  int decision = 1;\n  System.out.println(decision);\n }\n}\n");
    }

    @Test
    void compilesCatalogOwnedSourceAnchorsInDeterministicTracepointOrder() {
        JdwpPlanRequest request = request(List.of(
                point("z-point", 5),
                point("a-point", 4)));

        JdwpCollectionPlan plan = new JdwpPlanCompiler().compile(catalog(), request, moduleRoot);

        assertEquals(List.of("a-point", "z-point"),
                plan.tracepoints().stream().map(point -> point.tracepointId()).toList());
        assertEquals(serviceAnchor, plan.tracepoints().getFirst().sourceAnchor());
        assertEquals("fixture.Algorithm#schedule()V", plan.tracepoints().getFirst().methodKey());
    }

    @Test
    void preservesSparseCaptureHitsAndRejectsHitsOutsideMaximum() {
        JdwpTracepointRequest sparse = new JdwpTracepointRequest(
                "sparse", "fixture.Algorithm#schedule()V", 4, 5,
                List.of(1, 3, 5), JdwpCaptureSpec.stackOnly());

        JdwpCollectionPlan plan = new JdwpPlanCompiler().compile(
                catalog(), request(List.of(sparse)), moduleRoot);

        assertEquals(List.of(1, 3, 5), plan.tracepoints().getFirst().captureOnHits());
        assertThrows(PlanCompilationException.class, () -> new JdwpPlanCompiler().compile(
                catalog(), request(List.of(new JdwpTracepointRequest(
                        "outside", "fixture.Algorithm#schedule()V", 4, 5,
                        List.of(1, 6), JdwpCaptureSpec.stackOnly()))), moduleRoot));
    }

    @Test
    void rejectsUnknownMethodDuplicatePointAndLineOutsideMethod() {
        assertThrows(PlanCompilationException.class, () -> new JdwpPlanCompiler().compile(
                catalog(), request(List.of(new JdwpTracepointRequest(
                        "missing", "fixture.Missing#run()V", 1, 1,
                        JdwpCaptureSpec.stackOnly()))), moduleRoot));
        assertThrows(PlanCompilationException.class, () -> new JdwpPlanCompiler().compile(
                catalog(), request(List.of(
                        point("same", 4),
                        point("same", 5))), moduleRoot));
        assertThrows(PlanCompilationException.class, () -> new JdwpPlanCompiler().compile(
                catalog(), request(List.of(point("outside", 7))),
                moduleRoot));
    }

    @Test
    void usesCurrentSourceAndPreservesMissingFileCause() throws Exception {
        Path service = moduleRoot.resolve(serviceAnchor.sourceRelativePath());
        Files.writeString(service, "changed", StandardCharsets.UTF_8);
        JdwpCollectionPlan current = new JdwpPlanCompiler().compile(
                catalog(), request(List.of(point("changed", 4))), moduleRoot);
        assertEquals("changed", current.tracepoints().getFirst().tracepointId());

        Files.delete(service);
        PlanCompilationException missing = assertThrows(PlanCompilationException.class,
                () -> new JdwpPlanCompiler().compile(
                        catalog(), request(List.of(point("missing-file", 4))), moduleRoot));
        assertNotNull(missing.getCause());
    }

    private JdwpPlanRequest request(List<JdwpTracepointRequest> points) {
        return new JdwpPlanRequest(
                new PlanId("plan-1"), points, JdwpCollectionBudget.defaults(),
                "检查调度决策变量", NOW);
    }

    private JdwpTracepointRequest point(String id, int line) {
        return new JdwpTracepointRequest(
                id, "fixture.Algorithm#schedule()V", line, 3,
                JdwpCaptureSpec.stackOnly());
    }

    private MethodCatalog catalog() {
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"), new ContextId("context-1"),
                new AnalysisId("analysis-1"), new TargetTest("fixture.AlgorithmTest", "runs"),
                List.of(
                        new MethodCatalogEntry("fixture.AlgorithmTest#runs()V", targetAnchor, 0, true),
                        new MethodCatalogEntry("fixture.Algorithm#schedule()V", serviceAnchor, 1, false)),
                List.of(), List.of(), SnapshotCompleteness.COMPLETE, 2, 0, NOW);
    }

    private SourceAnchor source(
            String relative, String className, String methodName, String descriptor,
            int startLine, int endLine, String content) throws Exception {
        Path path = moduleRoot.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return new SourceAnchor(
                className, methodName, descriptor, relative, startLine, endLine);
    }
}
