package org.example.algorithmdebug.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCaptureMode;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CodePathMethodSelection;
import org.example.algorithmdebug.contracts.CodePathProjection;
import org.example.algorithmdebug.contracts.CodePathProjectionSource;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TraceProvenance;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationBindingStatus;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.evidence.EvidenceEligibilityEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionEvidenceViewFactoryTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final PlanId PLAN_ID = new PlanId("plan-1");
    private static final RunId RUN_ID = new RunId("run-1");
    private static final CollectionId COLLECTION_ID = new CollectionId("collection-1");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-1");
    private static final String METHOD_KEY = "fixture.Algorithm#solve()I";
    private static final ArtifactReference RAW = new ArtifactReference(
            "raw-1", "RAW", "collections/collection-1/raw/trace.jsonl",
            "application/x-ndjson", "a".repeat(64), 100);

    @Test
    void codePathTraceUsesTheBoundedDerivedInvocationRatherThanRawTrace(
            @TempDir Path directory) throws Exception {
        ObservationPredicate predicate = predicate(
                "predicate-change", ObservationOperator.VALUE_CHANGED,
                new ObservationSelector.ValueChanged(
                        "decision", new ObservationSelector.LongValue(1),
                        new ObservationSelector.LongValue(2)));
        InvestigationBinding binding = binding(predicate.predicateId());
        CodePathCollectionPlan plan = codePathPlan(binding);
        Path invocations = directory.resolve("codepath-invocations.jsonl");
        Files.writeString(invocations, """
                {"sequence":1,"methodRef":"fixture.Algorithm#solve()I","projections":[{"name":"decision","status":"VALUE","value":1}]}
                {"sequence":2,"methodRef":"fixture.Algorithm#solve()I","projections":[{"name":"decision","status":"VALUE","value":2}]}
                """);
        TraceProvenance provenance = provenance(1, Optional.of(1L), Optional.empty());
        MethodPathSummary summary = new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, EVIDENCE_ID, CASE_ID, ANALYSIS_ID,
                RUN_ID, PLAN_ID, COLLECTION_ID, RAW,
                List.of(new MethodPathSummary.MethodStatistic(
                        METHOD_KEY, 2, 2, 0, 0, provenance, provenance)),
                List.of(), List.of(), false, NOW);

        var view = new CollectionEvidenceViewFactory().fromCodePath(
                plan, summary, Optional.of(invocations), eligible(),
                ComparisonOutcome.NOT_COMPARED, List.of(predicate));

        assertEquals(List.of(
                        new ObservationSelector.LongValue(1),
                        new ObservationSelector.LongValue(2)),
                view.values("decision").orElseThrow().values());
        assertEquals(List.of(METHOD_KEY), view.observedMethodKeys());
        assertEquals(List.of(EVIDENCE_ID), view.evidenceIds());
    }

    @Test
    void jdwpSummaryConvertsCapturedScalarKindsWithoutReadingRawTrace() {
        ObservationPredicate predicate = predicate(
                "predicate-value", ObservationOperator.VALUE_EQUALS,
                new ObservationSelector.ValueEquals(
                        "candidate.id", new ObservationSelector.LongValue(7)));
        InvestigationBinding binding = binding(predicate.predicateId());
        JdwpCollectionPlan plan = jdwpPlan(binding);
        TraceProvenance provenance = provenance(1, Optional.empty(), Optional.of(1L));
        JdwpSnapshotSummary summary = new JdwpSnapshotSummary(
                SchemaVersions.JDWP_SNAPSHOT_SUMMARY, EVIDENCE_ID, CASE_ID, ANALYSIS_ID,
                RUN_ID, PLAN_ID, COLLECTION_ID, RAW,
                List.of(new JdwpSnapshotSummary.TracepointHit(
                        "decision", 1, 1, 1, "main", "Algorithm#solve:3",
                        Optional.of("()I"), Optional.of(0L), List.of(),
                        List.of(new JdwpSnapshotSummary.ProjectionFact(
                                "candidate.id",
                                JdwpSnapshotSummary.ProjectionStatus.CAPTURED,
                                Optional.of("INTEGER"), Optional.of("int"),
                                Optional.of("7"), false, Optional.empty(),
                                Optional.empty(), provenance)), provenance)),
                List.of(), false, NOW);

        var view = new CollectionEvidenceViewFactory().fromJdwp(
                plan, summary, eligible(), ComparisonOutcome.NOT_COMPARED,
                List.of(predicate));

        assertEquals(List.of(new ObservationSelector.LongValue(7)),
                view.values("candidate.id").orElseThrow().values());
        assertEquals(List.of(EVIDENCE_ID), view.evidenceIds());
    }

    private static CodePathCollectionPlan codePathPlan(InvestigationBinding binding) {
        CodePathMethodSelection selection = new CodePathMethodSelection(
                new MethodSelector(METHOD_KEY, "fixture.Algorithm", "solve", "()I"),
                List.of(new CodePathProjection(
                        "decision", CodePathProjectionSource.RETURN,
                        Optional.empty(), List.of(), true)));
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, PLAN_ID, CASE_ID, ANALYSIS_ID,
                new TargetTest("fixture.AlgorithmTest", "solves"), List.of(selection),
                Optional.empty(), List.of(), CodePathCaptureMode.TRACE, 1, 10_000,
                CollectionBudget.defaults(), "inspect decision", "did it change",
                InvestigationBindingStatus.STRUCTURED, Optional.of(binding), NOW);
    }

    private static JdwpCollectionPlan jdwpPlan(InvestigationBinding binding) {
        SourceAnchor anchor = new SourceAnchor(
                "fixture.Algorithm", "solve", "()I",
                "src/main/java/fixture/Algorithm.java", 1, 10);
        JdwpTracepointSpec tracepoint = new JdwpTracepointSpec(
                "decision", METHOD_KEY, anchor, 3, 10, 2, 2, 0, List.of(),
                new JdwpCaptureSpec(true, 8, 256, List.of("candidate.id")));
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN, PLAN_ID, CASE_ID, ANALYSIS_ID,
                new TargetTest("fixture.AlgorithmTest", "solves"), List.of(tracepoint),
                JdwpCollectionBudget.defaults(), "inspect decision", "what was selected",
                InvestigationBindingStatus.STRUCTURED, Optional.of(binding), NOW);
    }

    private static ObservationPredicate predicate(
            String id, ObservationOperator operator, ObservationSelector selector) {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, new ObservationPredicateId(id),
                CASE_ID, ANALYSIS_ID, new HypothesisId("hypothesis-1"),
                new EvidenceGapId("gap-1"), operator, selector, PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
    }

    private static InvestigationBinding binding(ObservationPredicateId predicateId) {
        return new InvestigationBinding(
                SchemaVersions.INVESTIGATION_BINDING, CASE_ID, ANALYSIS_ID,
                new EvidenceGapId("gap-1"), List.of(new HypothesisId("hypothesis-1")),
                List.of(predicateId), List.of());
    }

    private static TraceProvenance provenance(
            long line, Optional<Long> eventId, Optional<Long> sequence) {
        return new TraceProvenance(
                CASE_ID, RUN_ID, COLLECTION_ID, RAW, line,
                eventId, sequence, "DIRECT");
    }

    private static org.example.algorithmdebug.contracts.EvidenceEligibility eligible() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, false, ComparisonOutcome.NOT_COMPARED, true));
    }
}
