package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.BaselineVerification;
import org.example.algorithmdebug.contracts.CaseFingerprint;
import org.example.algorithmdebug.contracts.CaseLifecycleState;
import org.example.algorithmdebug.contracts.RunId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaselineStabilityServiceTest {

    private final BaselineStabilityService service = new BaselineStabilityService(2);
    private final CaseFingerprint fingerprint = TestFingerprints.sample("org.example.Test#case1", "2".repeat(64));

    @Test
    void marksBaselineStableAfterTwoMatchingRuns() {
        BaselineVerification first = service.start(fingerprint, new RunId("RUN-001"), "a".repeat(64));
        BaselineVerification second = service.record(first, new RunId("RUN-002"), "a".repeat(64));

        assertEquals(CaseLifecycleState.BASELINE_STABLE, second.state());
        assertEquals(2, second.observations().size());
    }

    @Test
    void marksSameExecutionConditionsUnstableWhenSemanticResultChanges() {
        BaselineVerification first = service.start(fingerprint, new RunId("RUN-001"), "a".repeat(64));
        BaselineVerification second = service.record(first, new RunId("RUN-002"), "b".repeat(64));

        assertEquals(CaseLifecycleState.BASELINE_UNSTABLE, second.state());
    }
}
