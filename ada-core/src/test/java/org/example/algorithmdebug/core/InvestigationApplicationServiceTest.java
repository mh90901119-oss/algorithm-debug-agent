package org.example.algorithmdebug.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.InvestigationEventArchive;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.InvestigationUpdateCommand;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.evidence.EvidenceEligibilityEvaluator;
import org.example.algorithmdebug.evidence.EvidenceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestigationApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final HypothesisId HYPOTHESIS_ID = new HypothesisId("hypothesis-1");
    private static final EvidenceGapId GAP_ID = new EvidenceGapId("gap-1");
    private static final ObservationPredicateId PREDICATE_ID =
            new ObservationPredicateId("predicate-method-observed");
    private static final String METHOD_KEY = "fixture.Algorithm#solve()V";

    @TempDir
    Path temporaryDirectory;

    private Path casesRoot;
    private BoundedDocumentMapper mapper;
    private AtomicDocumentWriter writer;
    private InvestigationApplicationService service;
    private InvestigationBinding binding;

    @BeforeEach
    void setUp() throws Exception {
        casesRoot = WorkspaceLayout.of(temporaryDirectory).projectCases(PROJECT_ID);
        Files.createDirectories(casesRoot);
        mapper = new BoundedDocumentMapper();
        writer = new AtomicDocumentWriter();
        CaseArchiveRepository archive = new CaseArchiveRepository(casesRoot, mapper, writer);
        TargetTest target = new TargetTest("fixture.TargetTest", "caseUnderTest");
        archive.createCase(new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, target,
                "fixture", "why", NOW));
        archive.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, ANALYSIS_ID, "continue", NOW));
        InvestigationTestFixture.archive(
                temporaryDirectory, PROJECT_ID, CASE_ID, ANALYSIS_ID, target,
                new SourceAnchor("fixture.Algorithm", "solve", "()V",
                        "src/main/java/fixture/Algorithm.java", 1, 20), NOW);
        appendMethodPredicate();
        service = new InvestigationApplicationService(
                casesRoot, mapper, writer, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        binding = new InvestigationBinding(
                SchemaVersions.INVESTIGATION_BINDING, CASE_ID, ANALYSIS_ID, GAP_ID,
                List.of(HYPOTHESIS_ID), List.of(PREDICATE_ID), List.of());
    }

    @Test
    void archivedPlanImmediatelyMarksItsGapPlanned() {
        var state = service.recordPlanBound(new PlanId("plan-1"), binding);

        assertEquals(EvidenceGapStatus.PLANNED, state.gaps().getFirst().status());
        assertEquals(7, state.lastSequence());
    }

    @Test
    void modelCommandsCanOnlyAppendOpenDomainObjectsAndCannotSetSystemStatus() {
        HypothesisId hypothesisId = new HypothesisId("hypothesis-2");
        EvidenceGapId gapId = new EvidenceGapId("gap-2");
        ObservationPredicateId predicateId = new ObservationPredicateId("predicate-2");
        SourceAnchor anchor = new SourceAnchor(
                "fixture.Algorithm", "solve", "()V",
                "src/main/java/fixture/Algorithm.java", 1, 20);
        var hypothesis = new org.example.algorithmdebug.contracts.investigation.HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, hypothesisId, CASE_ID, ANALYSIS_ID,
                "a second mechanism explains the result", HypothesisStatus.OPEN,
                List.of(anchor), List.of(), List.of(), List.of(gapId), NOW);
        var gap = new org.example.algorithmdebug.contracts.investigation.EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, gapId, CASE_ID, ANALYSIS_ID,
                "which mechanism executed", EvidenceGapStatus.OPEN,
                List.of(hypothesisId), List.of(predicateId), NOW);
        var predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, predicateId, CASE_ID, ANALYSIS_ID,
                hypothesisId, gapId, ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved(METHOD_KEY), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);

        service.update(new InvestigationUpdateCommand.AddHypothesis(
                CASE_ID, ANALYSIS_ID, hypothesis));
        service.update(new InvestigationUpdateCommand.AddEvidenceGap(
                CASE_ID, ANALYSIS_ID, gap));
        var state = service.update(new InvestigationUpdateCommand.RegisterPredicate(
                CASE_ID, ANALYSIS_ID, predicate));

        assertEquals(2, state.hypotheses().size());
        assertEquals(2, state.gaps().size());
        assertThrows(IllegalArgumentException.class, () ->
                new InvestigationUpdateCommand.AddHypothesis(
                        CASE_ID, ANALYSIS_ID,
                        new org.example.algorithmdebug.contracts.investigation.HypothesisRecord(
                                SchemaVersions.HYPOTHESIS_RECORD,
                                new HypothesisId("hypothesis-illegal"), CASE_ID, ANALYSIS_ID,
                                "illegal status", HypothesisStatus.SUPPORTED,
                                List.of(anchor), List.of(), List.of(), List.of(), NOW)));
        assertThrows(IllegalArgumentException.class, () -> service.update(
                new InvestigationUpdateCommand.RegisterPredicate(
                        CASE_ID, ANALYSIS_ID, predicate)));
    }

    @Test
    void confirmationEligibleFalseCriticalObservationClosesGapAndRefutesHypothesisExactlyOnce() {
        service.recordPlanBound(new PlanId("plan-1"), binding);

        var first = service.recordCollectedEvidence(binding, evidence(true, List.of()));
        var replayed = service.recordCollectedEvidence(binding, evidence(true, List.of()));

        assertEquals(EvidenceGapStatus.CLOSED, first.gaps().getFirst().status());
        assertEquals(HypothesisStatus.REFUTED, first.hypotheses().getFirst().status());
        assertEquals(1, first.evaluations().size());
        assertEquals(first.lastSequence(), replayed.lastSequence());
        assertEquals(first.evaluations(), replayed.evaluations());
    }

    @Test
    void clueOnlyObservationIsArchivedWithoutAdvancingGapOrHypothesis() {
        service.recordPlanBound(new PlanId("plan-1"), binding);

        var state = service.recordCollectedEvidence(binding, evidence(false, List.of()));

        assertEquals(EvidenceGapStatus.PLANNED, state.gaps().getFirst().status());
        assertEquals(HypothesisStatus.OPEN, state.hypotheses().getFirst().status());
        assertEquals(1, state.evaluations().size());
    }

    @Test
    void aSecondPlanCannotRewriteThePredicateOfAnAlreadyPlannedGap() {
        service.recordPlanBound(new PlanId("plan-1"), binding);

        assertThrows(IllegalArgumentException.class, () -> service.recordPlanBound(
                new PlanId("plan-2"), binding));
    }

    @Test
    void exactPlanReplayRepairsAnInterruptedPlannedTransition() throws Exception {
        service.recordPlanBound(new PlanId("plan-1"), binding);
        deleteEventsFrom(7);

        var repaired = service.recordPlanBound(new PlanId("plan-1"), binding);

        assertEquals(EvidenceGapStatus.PLANNED, repaired.gaps().getFirst().status());
        assertEquals(7, repaired.lastSequence());
    }

    @Test
    void exactEvidenceReplayRepairsInterruptedStatusReduction() throws Exception {
        service.recordPlanBound(new PlanId("plan-1"), binding);
        service.recordCollectedEvidence(binding, evidence(true, List.of()));
        deleteEventsFrom(10);

        var repaired = service.recordCollectedEvidence(binding, evidence(true, List.of()));

        assertEquals(EvidenceGapStatus.CLOSED, repaired.gaps().getFirst().status());
        assertEquals(HypothesisStatus.REFUTED, repaired.hypotheses().getFirst().status());
        assertEquals(1, repaired.evaluations().size());
    }

    @Test
    void exactEvidenceReplayRepairsInterruptedHypothesisReductionAfterGapClosed()
            throws Exception {
        service.recordPlanBound(new PlanId("plan-1"), binding);
        service.recordCollectedEvidence(binding, evidence(true, List.of()));
        deleteEventsFrom(11);

        var repaired = service.recordCollectedEvidence(binding, evidence(true, List.of()));

        assertEquals(EvidenceGapStatus.CLOSED, repaired.gaps().getFirst().status());
        assertEquals(HypothesisStatus.REFUTED, repaired.hypotheses().getFirst().status());
        assertEquals(1, repaired.evaluations().size());
    }

    private void appendMethodPredicate() {
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, PREDICATE_ID, CASE_ID, ANALYSIS_ID,
                HYPOTHESIS_ID, GAP_ID, ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved(METHOD_KEY), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW.plusSeconds(4));
        new InvestigationEventArchive(casesRoot, mapper, writer).appendEvent(
                new AnalysisIdentity(PROJECT_ID, CASE_ID, ANALYSIS_ID),
                new InvestigationEvent.PredicateRegistered(
                        SchemaVersions.INVESTIGATION_EVENT, "event-method-predicate",
                        CASE_ID, ANALYSIS_ID, 5, NOW.plusSeconds(4), predicate));
    }

    private EvidenceView evidence(boolean confirmationEligible, List<String> observedMethods) {
        var eligibility = new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, confirmationEligible, false,
                        ComparisonOutcome.NOT_COMPARED, true));
        return new EvidenceView(
                CASE_ID, ANALYSIS_ID, List.of(new EvidenceId("evidence-1")),
                EvidenceSourceCoverage.COMPLETE, eligibility, ComparisonOutcome.NOT_COMPARED,
                List.of(METHOD_KEY), observedMethods, List.of(), List.of(), List.of(), List.of(),
                List.of(), NOW.plusSeconds(20));
    }

    private void deleteEventsFrom(long firstSequence) throws Exception {
        Path eventsRoot = casesRoot.resolve(
                "case-1/analyses/analysis-1/investigation/events");
        try (var paths = Files.list(eventsRoot)) {
            for (Path path : paths.filter(value -> {
                String name = value.getFileName().toString();
                int separator = name.indexOf('-');
                return Long.parseLong(name.substring(0, separator)) >= firstSequence;
            }).toList()) {
                Files.delete(path);
            }
        }
    }
}
