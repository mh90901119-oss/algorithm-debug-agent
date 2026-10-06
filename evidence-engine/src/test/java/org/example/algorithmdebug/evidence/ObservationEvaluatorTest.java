package org.example.algorithmdebug.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceSourceCoverage;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ObservationEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-observation");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-observation");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-observation");
    private static final HypothesisId HYPOTHESIS_ID = new HypothesisId("hypothesis-observation");
    private static final EvidenceGapId GAP_ID = new EvidenceGapId("gap-observation");
    private static final ObservationSelector.ScalarValue ONE =
            new ObservationSelector.LongValue(1);
    private static final ObservationSelector.ScalarValue TWO =
            new ObservationSelector.LongValue(2);

    private final ObservationEvaluator evaluator = new ObservationEvaluator();

    @ParameterizedTest(name = "{0}-{1}")
    @MethodSource("allOperatorCases")
    void evaluatesEveryOperatorWithTrueFalseAndUnknown(
            ObservationOperator operator,
            ObservationTruth expected,
            ObservationSelector selector,
            EvidenceView evidence) {
        var evaluation = evaluator.evaluate(predicate(operator, selector), evidence);

        assertEquals(expected, evaluation.truth());
        assertEquals(CASE_ID, evaluation.caseId());
        assertEquals(ANALYSIS_ID, evaluation.analysisId());
        assertEquals(List.of(EVIDENCE_ID), evaluation.evidenceIds());
        assertEquals(evidence.sourceCoverage(), evaluation.sourceCoverage());
        assertEquals(expected != ObservationTruth.UNKNOWN
                        && evaluation.evidenceDisposition()
                        == EvidenceDisposition.CONFIRMATION_ELIGIBLE,
                evaluation.effectApplied());
    }

    @Test
    void completeNoMatchCanProduceFalse() {
        EvidenceView evidence = view(
                EvidenceSourceCoverage.COMPLETE, eligible(), ComparisonOutcome.NOT_COMPARED,
                List.of("fixture.Algorithm#solve()V"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());

        var result = evaluator.evaluate(predicate(
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Algorithm#solve()V")), evidence);

        assertEquals(ObservationTruth.FALSE, result.truth());
        assertEquals(HypothesisEffect.REFUTE, result.appliedEffect());
    }

    @Test
    void partialNoMatchAlwaysProducesUnknown() {
        EvidenceView evidence = view(
                EvidenceSourceCoverage.PARTIAL, eligible(), ComparisonOutcome.NOT_COMPARED,
                List.of("fixture.Algorithm#solve()V"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of("QUERY_PAGE_INCOMPLETE"));

        var result = evaluator.evaluate(predicate(
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Algorithm#solve()V")), evidence);

        assertEquals(ObservationTruth.UNKNOWN, result.truth());
        assertFalse(result.effectApplied());
        assertFalse(result.limitations().isEmpty());
    }

    @Test
    void changedFailureProducesClueOnlyAndDoesNotApplyEffect() {
        EvidenceView evidence = view(
                EvidenceSourceCoverage.COMPLETE, changedFailure(), ComparisonOutcome.CHANGED,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        var result = evaluator.evaluate(predicate(
                ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches()), evidence);

        assertEquals(ObservationTruth.FALSE, result.truth());
        assertEquals(EvidenceDisposition.CLUE_ONLY, result.evidenceDisposition());
        assertFalse(result.effectApplied());
        assertEquals(HypothesisEffect.NO_CHANGE, result.appliedEffect());
    }

    @Test
    void incomparableFailureProducesClueOnlyAndDoesNotApplyEffect() {
        EvidenceView evidence = view(
                EvidenceSourceCoverage.UNKNOWN, incomparableFailure(), ComparisonOutcome.INCOMPARABLE,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("FAILURE_BASELINE_INCOMPARABLE"));

        var result = evaluator.evaluate(predicate(
                ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches()), evidence);

        assertEquals(ObservationTruth.UNKNOWN, result.truth());
        assertEquals(EvidenceDisposition.CLUE_ONLY, result.evidenceDisposition());
        assertFalse(result.effectApplied());
    }

    @Test
    void invalidEvidenceIsRejectedBeforeEvaluation() {
        EvidenceView evidence = view(
                EvidenceSourceCoverage.COMPLETE, invalid(), ComparisonOutcome.NOT_COMPARED,
                List.of("fixture.Algorithm#solve()V"),
                List.of("fixture.Algorithm#solve()V"), List.of(), List.of(), List.of(),
                List.of(), List.of());

        var result = evaluator.evaluate(predicate(
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Algorithm#solve()V")), evidence);

        assertEquals(ObservationTruth.UNKNOWN, result.truth());
        assertEquals(EvidenceDisposition.INVALID, result.evidenceDisposition());
        assertFalse(result.effectApplied());
        assertTrue(result.limitations().contains(
                ObservationErrorCode.OBSERVATION_EVIDENCE_INVALID.name()));
    }

    @Test
    void samePredicateAndEvidenceHashProduceSameSemanticResult() {
        ObservationPredicate predicate = predicate(
                ObservationOperator.METHOD_OBSERVED,
                new ObservationSelector.MethodObserved("fixture.Algorithm#solve()V"));
        EvidenceView first = view(
                EvidenceSourceCoverage.COMPLETE, eligible(), ComparisonOutcome.NOT_COMPARED,
                List.of("fixture.Helper#run()V", "fixture.Algorithm#solve()V"),
                List.of("fixture.Helper#run()V", "fixture.Algorithm#solve()V"),
                List.of(), List.of(), List.of(), List.of(), List.of());
        EvidenceView reordered = view(
                EvidenceSourceCoverage.COMPLETE, eligible(), ComparisonOutcome.NOT_COMPARED,
                List.of("fixture.Algorithm#solve()V", "fixture.Helper#run()V"),
                List.of("fixture.Algorithm#solve()V", "fixture.Helper#run()V"),
                List.of(), List.of(), List.of(), List.of(), List.of());

        var firstResult = evaluator.evaluate(predicate, first);
        var replayed = evaluator.evaluate(predicate, reordered);

        assertEquals(firstResult.truth(), replayed.truth());
        assertEquals(firstResult.inputSha256(), replayed.inputSha256());
        assertEquals(firstResult.evaluationId(), replayed.evaluationId());
        assertEquals(firstResult.appliedEffect(), replayed.appliedEffect());
    }

    @Test
    void completeEvidenceWithoutAPathScopeCannotProvePathAbsence() {
        ObservationSelector.PathContains selector = new ObservationSelector.PathContains(
                List.of("fixture.A#a()V", "fixture.B#b()V"));
        EvidenceView evidence = complete(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        var result = evaluator.evaluate(
                predicate(ObservationOperator.PATH_CONTAINS, selector), evidence);

        assertEquals(ObservationTruth.UNKNOWN, result.truth());
        assertTrue(result.limitations().contains(
                ObservationErrorCode.OBSERVATION_VALUE_MISSING.name()));
    }

    private static Stream<Arguments> allOperatorCases() {
        String method = "fixture.Algorithm#solve()V";
        ObservationSelector.RecordExists recordSelector =
                new ObservationSelector.RecordExists("JDWP_HIT", "candidate.id", ONE);
        ObservationSelector.ValueEquals equalsSelector =
                new ObservationSelector.ValueEquals("candidate.id", ONE);
        ObservationSelector.ValueChanged changedSelector =
                new ObservationSelector.ValueChanged("candidate.id", ONE, TWO);
        ObservationSelector.CountCompare countSelector = new ObservationSelector.CountCompare(
                "JDWP_HIT", ObservationSelector.Comparator.GE, 2);
        ObservationSelector.PathContains pathSelector =
                new ObservationSelector.PathContains(List.of("fixture.A#a()V", "fixture.B#b()V"));
        return Stream.of(
                Arguments.of(ObservationOperator.METHOD_OBSERVED, ObservationTruth.TRUE,
                        new ObservationSelector.MethodObserved(method),
                        complete(List.of(method), List.of(method), List.of(), List.of(),
                                List.of(), List.of())),
                Arguments.of(ObservationOperator.METHOD_OBSERVED, ObservationTruth.FALSE,
                        new ObservationSelector.MethodObserved(method),
                        complete(List.of(method), List.of(), List.of(), List.of(),
                                List.of(), List.of())),
                Arguments.of(ObservationOperator.METHOD_OBSERVED, ObservationTruth.UNKNOWN,
                        new ObservationSelector.MethodObserved(method),
                        complete(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),

                Arguments.of(ObservationOperator.RECORD_EXISTS, ObservationTruth.TRUE,
                        recordSelector, complete(List.of(), List.of(),
                                List.of(new EvidenceView.RecordSet(
                                        "JDWP_HIT", "candidate.id", List.of(ONE),
                                        EvidenceSourceCoverage.COMPLETE)),
                                List.of(), List.of(), List.of())),
                Arguments.of(ObservationOperator.RECORD_EXISTS, ObservationTruth.FALSE,
                        recordSelector, complete(List.of(), List.of(),
                                List.of(new EvidenceView.RecordSet(
                                        "JDWP_HIT", "candidate.id", List.of(),
                                        EvidenceSourceCoverage.COMPLETE)),
                                List.of(), List.of(), List.of())),
                Arguments.of(ObservationOperator.RECORD_EXISTS, ObservationTruth.UNKNOWN,
                        recordSelector, partial(List.of(), List.of(),
                                List.of(new EvidenceView.RecordSet(
                                        "JDWP_HIT", "candidate.id", List.of(),
                                        EvidenceSourceCoverage.PARTIAL)),
                                List.of(), List.of(), List.of())),

                Arguments.of(ObservationOperator.VALUE_EQUALS, ObservationTruth.TRUE,
                        equalsSelector, complete(List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.ValueSeries(
                                        "candidate.id", List.of(ONE), true,
                                        EvidenceSourceCoverage.COMPLETE)),
                                List.of(), List.of())),
                Arguments.of(ObservationOperator.VALUE_EQUALS, ObservationTruth.FALSE,
                        equalsSelector, complete(List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.ValueSeries(
                                        "candidate.id", List.of(TWO), true,
                                        EvidenceSourceCoverage.COMPLETE)),
                                List.of(), List.of())),
                Arguments.of(ObservationOperator.VALUE_EQUALS, ObservationTruth.UNKNOWN,
                        equalsSelector, partial(List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.ValueSeries(
                                        "candidate.id", List.of(), true,
                                        EvidenceSourceCoverage.PARTIAL)),
                                List.of(), List.of())),

                Arguments.of(ObservationOperator.VALUE_CHANGED, ObservationTruth.TRUE,
                        changedSelector, complete(List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.ValueSeries(
                                        "candidate.id", List.of(ONE, TWO), true,
                                        EvidenceSourceCoverage.COMPLETE)),
                                List.of(), List.of())),
                Arguments.of(ObservationOperator.VALUE_CHANGED, ObservationTruth.FALSE,
                        changedSelector, complete(List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.ValueSeries(
                                        "candidate.id", List.of(ONE, ONE), true,
                                        EvidenceSourceCoverage.COMPLETE)),
                                List.of(), List.of())),
                Arguments.of(ObservationOperator.VALUE_CHANGED, ObservationTruth.UNKNOWN,
                        changedSelector, partial(List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.ValueSeries(
                                        "candidate.id", List.of(ONE), false,
                                        EvidenceSourceCoverage.PARTIAL)),
                                List.of(), List.of())),

                Arguments.of(ObservationOperator.COUNT_COMPARE, ObservationTruth.TRUE,
                        countSelector, complete(List.of(), List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.CountFact("JDWP_HIT", 3, true)),
                                List.of())),
                Arguments.of(ObservationOperator.COUNT_COMPARE, ObservationTruth.FALSE,
                        countSelector, complete(List.of(), List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.CountFact("JDWP_HIT", 1, true)),
                                List.of())),
                Arguments.of(ObservationOperator.COUNT_COMPARE, ObservationTruth.UNKNOWN,
                        countSelector, partial(List.of(), List.of(), List.of(), List.of(),
                                List.of(new EvidenceView.CountFact("JDWP_HIT", 1, false)),
                                List.of())),

                Arguments.of(ObservationOperator.PATH_CONTAINS, ObservationTruth.TRUE,
                        pathSelector, complete(List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of(new EvidenceView.PathFact(List.of(
                                        "fixture.A#a()V", "fixture.B#b()V", "fixture.C#c()V"))))),
                Arguments.of(ObservationOperator.PATH_CONTAINS, ObservationTruth.FALSE,
                        pathSelector, complete(List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of(new EvidenceView.PathFact(List.of(
                                        "fixture.A#a()V", "fixture.C#c()V"))))),
                Arguments.of(ObservationOperator.PATH_CONTAINS, ObservationTruth.UNKNOWN,
                        pathSelector, partial(List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of())),

                Arguments.of(ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                        ObservationTruth.TRUE,
                        new ObservationSelector.FailureFingerprintMatches(), fingerprint(
                                ComparisonOutcome.MATCHED, matchedFailure(),
                                EvidenceSourceCoverage.COMPLETE, List.of())),
                Arguments.of(ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                        ObservationTruth.FALSE,
                        new ObservationSelector.FailureFingerprintMatches(), fingerprint(
                                ComparisonOutcome.CHANGED, changedFailure(),
                                EvidenceSourceCoverage.COMPLETE, List.of())),
                Arguments.of(ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                        ObservationTruth.UNKNOWN,
                        new ObservationSelector.FailureFingerprintMatches(), fingerprint(
                                ComparisonOutcome.INCOMPARABLE, incomparableFailure(),
                                EvidenceSourceCoverage.UNKNOWN,
                                List.of("FAILURE_BASELINE_INCOMPARABLE"))));
    }

    private static ObservationPredicate predicate(
            ObservationOperator operator, ObservationSelector selector) {
        return new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE,
                new ObservationPredicateId("predicate-" + operator.name().toLowerCase()),
                CASE_ID, ANALYSIS_ID, HYPOTHESIS_ID, GAP_ID, operator, selector,
                PredicateRole.CRITICAL, HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, NOW);
    }

    private static EvidenceView complete(
            List<String> tracked,
            List<String> observed,
            List<EvidenceView.RecordSet> records,
            List<EvidenceView.ValueSeries> values,
            List<EvidenceView.CountFact> counts,
            List<EvidenceView.PathFact> paths) {
        return view(EvidenceSourceCoverage.COMPLETE, eligible(), ComparisonOutcome.NOT_COMPARED,
                tracked, observed, records, values, counts, paths, List.of());
    }

    private static EvidenceView partial(
            List<String> tracked,
            List<String> observed,
            List<EvidenceView.RecordSet> records,
            List<EvidenceView.ValueSeries> values,
            List<EvidenceView.CountFact> counts,
            List<EvidenceView.PathFact> paths) {
        return view(EvidenceSourceCoverage.PARTIAL, eligible(), ComparisonOutcome.NOT_COMPARED,
                tracked, observed, records, values, counts, paths,
                List.of("QUERY_PAGE_INCOMPLETE"));
    }

    private static EvidenceView fingerprint(
            ComparisonOutcome outcome,
            EvidenceEligibility eligibility,
            EvidenceSourceCoverage coverage,
            List<String> limitations) {
        return view(coverage, eligibility, outcome, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), limitations);
    }

    private static EvidenceView view(
            EvidenceSourceCoverage coverage,
            EvidenceEligibility eligibility,
            ComparisonOutcome outcome,
            List<String> tracked,
            List<String> observed,
            List<EvidenceView.RecordSet> records,
            List<EvidenceView.ValueSeries> values,
            List<EvidenceView.CountFact> counts,
            List<EvidenceView.PathFact> paths,
            List<String> limitations) {
        return new EvidenceView(
                CASE_ID, ANALYSIS_ID, List.of(EVIDENCE_ID), coverage, eligibility, outcome,
                tracked, observed, records, values, counts, paths, limitations, NOW);
    }

    static EvidenceEligibility eligible() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, false, ComparisonOutcome.NOT_COMPARED, true));
    }

    private static EvidenceEligibility matchedFailure() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, true, ComparisonOutcome.MATCHED, true));
    }

    static EvidenceEligibility changedFailure() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, true, ComparisonOutcome.CHANGED, true));
    }

    private static EvidenceEligibility incomparableFailure() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        true, true, true, ComparisonOutcome.INCOMPARABLE, true));
    }

    private static EvidenceEligibility invalid() {
        return new EvidenceEligibilityEvaluator().evaluate(
                new EvidenceEligibilityEvaluator.Context(
                        false, true, false, ComparisonOutcome.NOT_COMPARED, true));
    }
}
