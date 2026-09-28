package org.example.algorithmdebug.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.EvidenceEligibilityReason;
import org.junit.jupiter.api.Test;

class EvidenceEligibilityTest {

    private final EvidenceEligibilityEvaluator evaluator = new EvidenceEligibilityEvaluator();

    @Test
    void passingCollectionWithoutReferenceRunIsConfirmationEligible() {
        var eligibility = evaluate(true, true, false, ComparisonOutcome.NOT_COMPARED, true);

        assertTrue(eligibility.artifactReadable());
        assertFalse(eligibility.baselineRequired());
        assertFalse(eligibility.baselineComparable());
        assertTrue(eligibility.confirmationEligible());
        assertTrue(eligibility.reasonCodes().contains(
                EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name()));
    }

    @Test
    void failedCollectionRequiresMatchedFingerprint() {
        var eligibility = evaluate(true, true, true, ComparisonOutcome.MATCHED, true);

        assertTrue(eligibility.baselineRequired());
        assertTrue(eligibility.baselineComparable());
        assertTrue(eligibility.failureFingerprintMatched());
        assertTrue(eligibility.confirmationEligible());
    }

    @Test
    void changedFailureIsReadableButClueOnly() {
        var eligibility = evaluate(true, true, true, ComparisonOutcome.CHANGED, true);

        assertTrue(eligibility.artifactReadable());
        assertTrue(eligibility.baselineComparable());
        assertFalse(eligibility.failureFingerprintMatched());
        assertFalse(eligibility.confirmationEligible());
        assertTrue(eligibility.reasonCodes().contains(
                EvidenceEligibilityReason.FAILURE_FINGERPRINT_CHANGED.name()));
    }

    @Test
    void incomparableFailureIsReadableButClueOnly() {
        var eligibility = evaluate(true, true, true, ComparisonOutcome.INCOMPARABLE, true);

        assertTrue(eligibility.artifactReadable());
        assertFalse(eligibility.baselineComparable());
        assertFalse(eligibility.confirmationEligible());
        assertTrue(eligibility.reasonCodes().contains(
                EvidenceEligibilityReason.FAILURE_FINGERPRINT_INCOMPARABLE.name()));
    }

    @Test
    void zeroHitFailsObligationWithoutBecomingUnreadable() {
        var eligibility = evaluate(true, true, false, ComparisonOutcome.NOT_COMPARED, false);

        assertTrue(eligibility.artifactReadable());
        assertTrue(eligibility.collectionComplete());
        assertFalse(eligibility.obligationSatisfied());
        assertFalse(eligibility.confirmationEligible());
    }

    @Test
    void truncatedCollectionIsReadableButIncomplete() {
        var eligibility = evaluate(true, false, false, ComparisonOutcome.NOT_COMPARED, true);

        assertTrue(eligibility.artifactReadable());
        assertFalse(eligibility.collectionComplete());
        assertFalse(eligibility.confirmationEligible());
    }

    @Test
    void invalidArtifactIsNeverEligible() {
        var eligibility = evaluate(false, true, false, ComparisonOutcome.NOT_COMPARED, true);

        assertFalse(eligibility.artifactReadable());
        assertFalse(eligibility.confirmationEligible());
    }

    private org.example.algorithmdebug.contracts.EvidenceEligibility evaluate(
            boolean artifactReadable,
            boolean collectionComplete,
            boolean targetFailed,
            ComparisonOutcome outcome,
            boolean obligationSatisfied) {
        return evaluator.evaluate(new EvidenceEligibilityEvaluator.Context(
                artifactReadable, collectionComplete, targetFailed, outcome,
                obligationSatisfied));
    }
}
