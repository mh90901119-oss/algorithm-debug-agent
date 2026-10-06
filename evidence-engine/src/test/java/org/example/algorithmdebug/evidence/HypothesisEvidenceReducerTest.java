package org.example.algorithmdebug.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluationId;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.junit.jupiter.api.Test;

class HypothesisEvidenceReducerTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-reducer");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-reducer");
    private static final HypothesisId HYPOTHESIS_ID = new HypothesisId("hypothesis-reducer");
    private static final EvidenceGapId GAP_ID = new EvidenceGapId("gap-reducer");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-reducer");
    private static final SourceAnchor ANCHOR = new SourceAnchor(
            "fixture.Algorithm", "solve", "()V", "src/main/java/fixture/Algorithm.java", 1, 10);

    private final ObservationEvaluator observationEvaluator = new ObservationEvaluator();
    private final HypothesisEvidenceReducer reducer = new HypothesisEvidenceReducer();

    @Test
    void criticalRefuteMakesHypothesisRefuted() {
        ObservationPredicate predicate = methodPredicate(
                "predicate-refute", PredicateRole.CRITICAL, "fixture.Algorithm#missing()V");
        ObservationEvaluation evaluation = observationEvaluator.evaluate(
                predicate, methodView(List.of("fixture.Algorithm#missing()V"), List.of()));

        var result = reducer.reduce(
                hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                List.of(predicate), List.of(gap(EvidenceGapStatus.CLOSED, List.of(predicate))),
                List.of(evaluation));

        assertEquals(HypothesisStatus.REFUTED, result.newStatus());
        assertEquals(List.of(evaluation.evaluationId()), result.contradictingEvaluationIds());
    }

    @Test
    void allCriticalSupportAndClosedGapsMakeSupported() {
        ObservationPredicate first = methodPredicate(
                "predicate-first", PredicateRole.CRITICAL, "fixture.Algorithm#solve()V");
        ObservationPredicate second = methodPredicate(
                "predicate-second", PredicateRole.CRITICAL, "fixture.Helper#run()V");
        ObservationEvaluation firstResult = observationEvaluator.evaluate(
                first, methodView(List.of("fixture.Algorithm#solve()V"),
                        List.of("fixture.Algorithm#solve()V")));
        ObservationEvaluation secondResult = observationEvaluator.evaluate(
                second, methodView(List.of("fixture.Helper#run()V"),
                        List.of("fixture.Helper#run()V")));

        var result = reducer.reduce(
                hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                List.of(first, second),
                List.of(gap(EvidenceGapStatus.CLOSED, List.of(first, second))),
                List.of(firstResult, secondResult));

        assertEquals(HypothesisStatus.SUPPORTED, result.newStatus());
        assertEquals(List.of(firstResult.evaluationId(), secondResult.evaluationId()),
                result.supportingEvaluationIds());
    }

    @Test
    void unknownCriticalMakesInconclusive() {
        ObservationPredicate predicate = methodPredicate(
                "predicate-unknown", PredicateRole.CRITICAL, "fixture.Algorithm#solve()V");
        ObservationEvaluation evaluation = observationEvaluator.evaluate(
                predicate, methodView(List.of(), List.of()));

        var result = reducer.reduce(
                hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                List.of(predicate), List.of(gap(EvidenceGapStatus.OPEN, List.of(predicate))),
                List.of(evaluation));

        assertEquals(HypothesisStatus.INCONCLUSIVE, result.newStatus());
        assertTrue(result.limitations().stream().anyMatch(value -> value.contains(
                predicate.predicateId().value())));
    }

    @Test
    void corroboratingEvidenceCannotAloneMakeSupportedOrRefuted() {
        ObservationPredicate predicate = methodPredicate(
                "predicate-corroborating", PredicateRole.CORROBORATING,
                "fixture.Algorithm#solve()V");
        ObservationEvaluation support = observationEvaluator.evaluate(
                predicate, methodView(List.of("fixture.Algorithm#solve()V"),
                        List.of("fixture.Algorithm#solve()V")));

        var supportedResult = reducer.reduce(
                hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                List.of(predicate), List.of(gap(EvidenceGapStatus.CLOSED, List.of(predicate))),
                List.of(support));

        ObservationEvaluation contradiction = observationEvaluator.evaluate(
                predicate, methodView(List.of("fixture.Algorithm#solve()V"), List.of()));
        var contradictedResult = reducer.reduce(
                hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                List.of(predicate), List.of(gap(EvidenceGapStatus.CLOSED, List.of(predicate))),
                List.of(contradiction));

        assertEquals(HypothesisStatus.OPEN, supportedResult.newStatus());
        assertEquals(List.of(support.evaluationId()), supportedResult.supportingEvaluationIds());
        assertEquals(HypothesisStatus.OPEN, contradictedResult.newStatus());
        assertEquals(List.of(contradiction.evaluationId()),
                contradictedResult.contradictingEvaluationIds());
    }

    @Test
    void addingUnknownCriticalMovesSupportedToInconclusive() {
        ObservationPredicate supportedPredicate = methodPredicate(
                "predicate-supported", PredicateRole.CRITICAL, "fixture.Algorithm#solve()V");
        ObservationPredicate addedPredicate = methodPredicate(
                "predicate-added", PredicateRole.CRITICAL, "fixture.Helper#run()V");
        ObservationEvaluation supported = observationEvaluator.evaluate(
                supportedPredicate, methodView(List.of("fixture.Algorithm#solve()V"),
                        List.of("fixture.Algorithm#solve()V")));
        ObservationEvaluation unknown = observationEvaluator.evaluate(
                addedPredicate, methodView(List.of(), List.of()));

        var result = reducer.reduce(
                hypothesis(HypothesisStatus.SUPPORTED,
                        List.of(supported.evaluationId()), List.of()),
                List.of(supportedPredicate, addedPredicate),
                List.of(gap(EvidenceGapStatus.CLOSED,
                        List.of(supportedPredicate, addedPredicate))),
                List.of(supported, unknown));

        assertEquals(HypothesisStatus.INCONCLUSIVE, result.newStatus());
    }

    @Test
    void unknownReplayDoesNotEraseConfirmedSupportForSamePredicate() {
        ObservationPredicate predicate = methodPredicate(
                "predicate-replayed", PredicateRole.CRITICAL, "fixture.Algorithm#solve()V");
        ObservationEvaluation supported = observationEvaluator.evaluate(
                predicate, methodView(List.of("fixture.Algorithm#solve()V"),
                        List.of("fixture.Algorithm#solve()V")));
        EvidenceView partial = new EvidenceView(
                CASE_ID, ANALYSIS_ID, List.of(new EvidenceId("evidence-partial")),
                EvidenceSourceCoverage.PARTIAL, ObservationEvaluatorTest.eligible(),
                ComparisonOutcome.NOT_COMPARED, List.of("fixture.Algorithm#solve()V"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("QUERY_PAGE_INCOMPLETE"), NOW);
        ObservationEvaluation unknown = observationEvaluator.evaluate(predicate, partial);

        var result = reducer.reduce(
                hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                List.of(predicate), List.of(gap(EvidenceGapStatus.CLOSED, List.of(predicate))),
                List.of(supported, unknown));

        assertEquals(HypothesisStatus.SUPPORTED, result.newStatus());
        assertEquals(List.of(supported.evaluationId()), result.supportingEvaluationIds());
    }

    @Test
    void refutedIsTerminal() {
        ObservationEvaluationId contradiction = new ObservationEvaluationId("evaluation-old");

        var result = reducer.reduce(
                hypothesis(HypothesisStatus.REFUTED, List.of(), List.of(contradiction)),
                List.of(), List.of(gap(EvidenceGapStatus.CLOSED, List.of())), List.of());

        assertEquals(HypothesisStatus.REFUTED, result.newStatus());
        assertEquals(List.of(contradiction), result.contradictingEvaluationIds());
    }

    @Test
    void clueOnlyEvaluationNeverChangesStatus() {
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE,
                new ObservationPredicateId("predicate-fingerprint"), CASE_ID, ANALYSIS_ID,
                HYPOTHESIS_ID, GAP_ID, ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches(), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE, HypothesisEffect.NO_CHANGE, NOW);
        EvidenceView evidence = new EvidenceView(
                CASE_ID, ANALYSIS_ID, List.of(EVIDENCE_ID), EvidenceSourceCoverage.COMPLETE,
                ObservationEvaluatorTest.changedFailure(), ComparisonOutcome.CHANGED,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), NOW);
        ObservationEvaluation clue = observationEvaluator.evaluate(predicate, evidence);

        var result = reducer.reduce(
                hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                List.of(predicate), List.of(gap(EvidenceGapStatus.CLOSED, List.of(predicate))),
                List.of(clue));

        assertEquals(HypothesisStatus.INCONCLUSIVE, result.newStatus());
        assertTrue(result.contradictingEvaluationIds().isEmpty());
    }

    @Test
    void rejectsEvaluationWhoseAppliedEffectContradictsFrozenPredicate() {
        ObservationPredicate predicate = methodPredicate(
                "predicate-forged", PredicateRole.CRITICAL, "fixture.Algorithm#solve()V");
        ObservationEvaluation valid = observationEvaluator.evaluate(
                predicate, methodView(List.of("fixture.Algorithm#solve()V"),
                        List.of("fixture.Algorithm#solve()V")));
        ObservationEvaluation forged = new ObservationEvaluation(
                valid.schemaVersion(), valid.evaluationId(), valid.caseId(), valid.analysisId(),
                valid.predicateId(), valid.truth(), valid.evidenceIds(), valid.sourceCoverage(),
                EvidenceDisposition.CONFIRMATION_ELIGIBLE, false, HypothesisEffect.NO_CHANGE,
                valid.limitations(), valid.inputSha256(), valid.evaluatedAt(),
                valid.evaluatorVersion());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> reducer.reduce(
                        hypothesis(HypothesisStatus.OPEN, List.of(), List.of()),
                        List.of(predicate),
                        List.of(gap(EvidenceGapStatus.CLOSED, List.of(predicate))),
                        List.of(forged)));
    }

    private static ObservationPredicate methodPredicate(
            String id, PredicateRole role, String methodKey) {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, new ObservationPredicateId(id),
                CASE_ID, ANALYSIS_ID, HYPOTHESIS_ID, GAP_ID,
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved(methodKey), role,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
    }

    private static EvidenceView methodView(List<String> tracked, List<String> observed) {
        return new EvidenceView(
                CASE_ID, ANALYSIS_ID, List.of(EVIDENCE_ID), EvidenceSourceCoverage.COMPLETE,
                ObservationEvaluatorTest.eligible(), ComparisonOutcome.NOT_COMPARED,
                tracked, observed, List.of(), List.of(), List.of(), List.of(), List.of(), NOW);
    }

    private static HypothesisRecord hypothesis(
            HypothesisStatus status,
            List<ObservationEvaluationId> support,
            List<ObservationEvaluationId> contradiction) {
        return new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, HYPOTHESIS_ID, CASE_ID, ANALYSIS_ID,
                "The selected branch produces the wrong candidate", status,
                List.of(ANCHOR), support, contradiction, List.of(GAP_ID), NOW);
    }

    private static EvidenceGap gap(
            EvidenceGapStatus status, List<ObservationPredicate> predicates) {
        return new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, GAP_ID, CASE_ID, ANALYSIS_ID,
                "Which branch is selected?", status, List.of(HYPOTHESIS_ID),
                predicates.stream().map(ObservationPredicate::predicateId).toList(), NOW);
    }
}
