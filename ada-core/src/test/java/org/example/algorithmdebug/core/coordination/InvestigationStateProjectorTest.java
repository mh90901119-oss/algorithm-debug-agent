package org.example.algorithmdebug.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisEvaluation;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluationId;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.junit.jupiter.api.Test;

class InvestigationStateProjectorTest {
    static final CaseId CASE_ID = new CaseId("case-1");
    static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    static final HypothesisId HYPOTHESIS_ID = new HypothesisId("hypothesis-1");
    static final EvidenceGapId GAP_ID = new EvidenceGapId("gap-1");
    static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    private final InvestigationStateProjector projector = new InvestigationStateProjector();

    @Test
    void problemFrameMustBeFirstAndUnique() {
        List<InvestigationEvent> events = initialEvents();

        assertThrows(IllegalArgumentException.class, () -> projector.project(events.subList(1, 2)));

        List<InvestigationEvent> duplicateFrame = new ArrayList<>();
        duplicateFrame.add(events.getFirst());
        duplicateFrame.add(new InvestigationEvent.ProblemFrameDefined(
                SchemaVersions.INVESTIGATION_EVENT,
                "event-frame-duplicate",
                CASE_ID,
                ANALYSIS_ID,
                2,
                NOW.plusSeconds(2),
                problemFrame()));
        assertThrows(IllegalArgumentException.class, () -> projector.project(duplicateFrame));
    }

    @Test
    void refutedHypothesisCannotReturnToSupported() {
        List<InvestigationEvent> events = supportedOrRefutedEvents(HypothesisEffect.REFUTE);
        events = new ArrayList<>(events);
        events.add(new InvestigationEvent.HypothesisStatusChanged(
                SchemaVersions.INVESTIGATION_EVENT,
                "event-illegal-support",
                CASE_ID,
                ANALYSIS_ID,
                7,
                NOW.plusSeconds(7),
                HYPOTHESIS_ID,
                HypothesisStatus.OPEN,
                HypothesisStatus.SUPPORTED,
                new HypothesisEvaluation(
                        HYPOTHESIS_ID,
                        HypothesisStatus.OPEN,
                        HypothesisStatus.SUPPORTED,
                        List.of(new ObservationEvaluationId("evaluation-1")),
                        List.of(),
                        List.of())));

        List<InvestigationEvent> invalid = events;
        assertThrows(IllegalArgumentException.class, () -> projector.project(invalid));
    }

    @Test
    void addingCriticalUnknownMovesSupportedToInconclusive() {
        List<InvestigationEvent> events = new ArrayList<>(supportedOrRefutedEvents(HypothesisEffect.SUPPORT));
        ObservationPredicate unknown = predicate(
                "predicate-unknown", HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE);
        events.add(new InvestigationEvent.PredicateRegistered(
                SchemaVersions.INVESTIGATION_EVENT,
                "event-predicate-unknown",
                CASE_ID,
                ANALYSIS_ID,
                7,
                NOW.plusSeconds(7),
                unknown));

        InvestigationState state = projector.project(events);

        assertEquals(HypothesisStatus.INCONCLUSIVE, state.hypotheses().getFirst().status());
        assertEquals(
                List.of(new ObservationPredicateId("predicate-unknown")),
                projector.unevaluatedCriticalPredicateIds(state));
    }

    @Test
    void replayIsRepeatableAndRejectsOutOfOrderInput() {
        List<InvestigationEvent> events = supportedOrRefutedEvents(HypothesisEffect.SUPPORT);

        assertEquals(projector.project(events), projector.project(List.copyOf(events)));

        List<InvestigationEvent> outOfOrder = new ArrayList<>(events);
        Collections.swap(outOfOrder, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> projector.project(outOfOrder));
    }

    static List<InvestigationEvent> initialEvents() {
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD,
                HYPOTHESIS_ID,
                CASE_ID,
                ANALYSIS_ID,
                "candidate filter removed the expected choice",
                HypothesisStatus.OPEN,
                List.of(anchor()),
                List.of(),
                List.of(),
                List.of(GAP_ID),
                NOW);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP,
                GAP_ID,
                CASE_ID,
                ANALYSIS_ID,
                "was the expected candidate observed",
                EvidenceGapStatus.OPEN,
                List.of(HYPOTHESIS_ID),
                List.of(),
                NOW);
        return new ArrayList<>(List.of(
                new InvestigationEvent.ProblemFrameDefined(
                        SchemaVersions.INVESTIGATION_EVENT,
                        "event-frame", CASE_ID, ANALYSIS_ID, 1, NOW, problemFrame()),
                new InvestigationEvent.HypothesisAdded(
                        SchemaVersions.INVESTIGATION_EVENT,
                        "event-hypothesis", CASE_ID, ANALYSIS_ID, 2, NOW.plusSeconds(2), hypothesis),
                new InvestigationEvent.EvidenceGapAdded(
                        SchemaVersions.INVESTIGATION_EVENT,
                        "event-gap", CASE_ID, ANALYSIS_ID, 3, NOW.plusSeconds(3), gap)));
    }

    static List<InvestigationEvent> supportedOrRefutedEvents(HypothesisEffect effect) {
        List<InvestigationEvent> events = initialEvents();
        ObservationPredicate predicate = predicate("predicate-1", effect, HypothesisEffect.NO_CHANGE);
        events.add(new InvestigationEvent.PredicateRegistered(
                SchemaVersions.INVESTIGATION_EVENT,
                "event-predicate", CASE_ID, ANALYSIS_ID, 4, NOW.plusSeconds(4), predicate));
        ObservationEvaluationId evaluationId = new ObservationEvaluationId("evaluation-1");
        ObservationEvaluation evaluation = new ObservationEvaluation(
                SchemaVersions.OBSERVATION_EVALUATION,
                evaluationId,
                CASE_ID,
                ANALYSIS_ID,
                predicate.predicateId(),
                ObservationTruth.TRUE,
                List.of(new EvidenceId("evidence-1")),
                EvidenceSourceCoverage.COMPLETE,
                EvidenceDisposition.CONFIRMATION_ELIGIBLE,
                true,
                effect,
                List.of(),
                "a".repeat(64),
                NOW.plusSeconds(5),
                "1.0");
        events.add(new InvestigationEvent.ObservationEvaluated(
                SchemaVersions.INVESTIGATION_EVENT,
                "event-evaluation", CASE_ID, ANALYSIS_ID, 5, NOW.plusSeconds(5), evaluation));
        HypothesisStatus status = effect == HypothesisEffect.REFUTE
                ? HypothesisStatus.REFUTED : HypothesisStatus.SUPPORTED;
        HypothesisEvaluation hypothesisEvaluation = new HypothesisEvaluation(
                HYPOTHESIS_ID,
                HypothesisStatus.OPEN,
                status,
                effect == HypothesisEffect.SUPPORT ? List.of(evaluationId) : List.of(),
                effect == HypothesisEffect.REFUTE ? List.of(evaluationId) : List.of(),
                List.of());
        events.add(new InvestigationEvent.HypothesisStatusChanged(
                SchemaVersions.INVESTIGATION_EVENT,
                "event-status", CASE_ID, ANALYSIS_ID, 6, NOW.plusSeconds(6),
                HYPOTHESIS_ID, HypothesisStatus.OPEN, status, hypothesisEvaluation));
        return events;
    }

    private static ProblemFrame problemFrame() {
        return new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME,
                new ProblemFrameId("frame-1"),
                CASE_ID,
                ANALYSIS_ID,
                "scheduler selected the wrong candidate",
                "candidate A is selected",
                "candidate B is selected",
                new TargetTest("fixture.SchedulerTest", "selectsCandidate"),
                List.of(anchor()),
                List.of("run:run-1"),
                List.of("which filter removed candidate A"),
                NOW);
    }

    private static ObservationPredicate predicate(
            String id, HypothesisEffect onTrue, HypothesisEffect onFalse) {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE,
                new ObservationPredicateId(id),
                CASE_ID,
                ANALYSIS_ID,
                HYPOTHESIS_ID,
                GAP_ID,
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Scheduler#select()V"),
                PredicateRole.CRITICAL,
                onTrue,
                onFalse,
                HypothesisEffect.NO_CHANGE,
                NOW);
    }

    private static SourceAnchor anchor() {
        return new SourceAnchor(
                "fixture.Scheduler", "select", "()V",
                "src/main/java/fixture/Scheduler.java", 10, 20);
    }
}
