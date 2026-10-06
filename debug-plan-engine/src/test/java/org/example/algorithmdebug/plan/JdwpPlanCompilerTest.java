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
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
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

        JdwpCollectionPlan plan = compile(request);

        assertEquals(List.of("a-point", "z-point"),
                plan.tracepoints().stream().map(point -> point.tracepointId()).toList());
        assertEquals(serviceAnchor, plan.tracepoints().getFirst().sourceAnchor());
        assertEquals("fixture.Algorithm#schedule()V", plan.tracepoints().getFirst().methodKey());
        assertEquals("Which state selected the branch?", plan.questionToAnswer());
    }

    @Test
    void preservesFirstAndPeriodicSamplingAndRejectsInvalidPolicy() {
        JdwpTracepointRequest sampled = new JdwpTracepointRequest(
                "sampled", "fixture.Algorithm#schedule()V", 4,
                100, 20, 5, 10, List.of(), JdwpCaptureSpec.stackOnly());

        JdwpCollectionPlan plan = compile(request(List.of(sampled)));

        assertEquals(5, plan.tracepoints().getFirst().captureFirstMatchedHits());
        assertEquals(10, plan.tracepoints().getFirst().captureEveryMatchedHits());
        assertThrows(PlanCompilationException.class, () -> compile(
                request(List.of(new JdwpTracepointRequest(
                        "invalid", "fixture.Algorithm#schedule()V", 4,
                        5, 3, 0, 0, List.of(), JdwpCaptureSpec.stackOnly())))));
    }

    @Test
    void rejectsUnknownMethodDuplicatePointAndLineOutsideMethod() {
        assertThrows(PlanCompilationException.class, () -> compile(
                request(List.of(new JdwpTracepointRequest(
                        "missing", "fixture.Missing#run()V", 1,
                        1, 1, 1, 0, List.of(), JdwpCaptureSpec.stackOnly())))));
        assertThrows(PlanCompilationException.class, () -> compile(request(List.of(
                        point("same", 4),
                        point("same", 5)))));
        assertThrows(PlanCompilationException.class,
                () -> compile(request(List.of(point("outside", 7)))));
    }

    @Test
    void usesCurrentSourceAndPreservesMissingFileCause() throws Exception {
        Path service = moduleRoot.resolve(serviceAnchor.sourceRelativePath());
        Files.writeString(service, "changed", StandardCharsets.UTF_8);
        JdwpCollectionPlan current = compile(request(List.of(point("changed", 4))));
        assertEquals("changed", current.tracepoints().getFirst().tracepointId());

        Files.delete(service);
        PlanCompilationException missing = assertThrows(PlanCompilationException.class,
                () -> compile(request(List.of(point("missing-file", 4)))));
        assertNotNull(missing.getCause());
    }

    private JdwpPlanRequest request(List<JdwpTracepointRequest> points) {
        return new JdwpPlanRequest(
                new PlanId("plan-1"), points, JdwpCollectionBudget.defaults(),
                "Inspect the decision state",
                new InvestigationBindingRequest(
                        "Which state selected the branch?", new EvidenceGapId("gap-1"),
                        List.of(new HypothesisId("hypothesis-1")),
                        List.of(new ObservationPredicateId("predicate-1")), List.of()),
                NOW);
    }

    private JdwpCollectionPlan compile(JdwpPlanRequest request) {
        MethodCatalog catalog = catalog();
        return new JdwpPlanCompiler().compile(catalog, state(catalog), request, moduleRoot);
    }

    private InvestigationState state(MethodCatalog catalog) {
        HypothesisId hypothesisId = new HypothesisId("hypothesis-1");
        EvidenceGapId gapId = new EvidenceGapId("gap-1");
        ObservationPredicateId predicateId = new ObservationPredicateId("predicate-1");
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, hypothesisId,
                catalog.caseId(), catalog.analysisId(), "a runtime value selected the branch",
                HypothesisStatus.OPEN, List.of(serviceAnchor), List.of(), List.of(),
                List.of(gapId), NOW);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, gapId, catalog.caseId(), catalog.analysisId(),
                "which state selected the branch", EvidenceGapStatus.OPEN,
                List.of(hypothesisId), List.of(predicateId), NOW);
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, predicateId,
                catalog.caseId(), catalog.analysisId(), hypothesisId, gapId,
                ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches(), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"),
                catalog.caseId(), catalog.analysisId(), "unexpected branch",
                "expected branch", "actual branch", catalog.targetTest(),
                List.of(serviceAnchor), List.of("run:run-1"),
                List.of("which state selected the branch"), NOW);
        return new InvestigationState(
                SchemaVersions.INVESTIGATION_STATE, catalog.caseId(), catalog.analysisId(),
                frame, List.of(hypothesis), List.of(gap), List.of(predicate), List.of(),
                4, List.of());
    }

    private JdwpTracepointRequest point(String id, int line) {
        return new JdwpTracepointRequest(
                id, "fixture.Algorithm#schedule()V", line,
                100, 20, 5, 5, List.of(), JdwpCaptureSpec.stackOnly());
    }

    private MethodCatalog catalog() {
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG,
                new CaseId("case-1"), new AnalysisId("analysis-1"), new TargetTest("fixture.AlgorithmTest", "runs"),
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
