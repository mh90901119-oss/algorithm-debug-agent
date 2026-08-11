package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaseFingerprintTest {

    @Test
    void separatesPreRunFingerprintFromScheduleResultHash() {
        CaseFingerprint fingerprint = sampleFingerprint("org.example.ScheduleTest#case1", "1".repeat(64));
        ExecutionIdentity identity = new ExecutionIdentity(fingerprint, "9".repeat(64));

        assertEquals(fingerprint, identity.caseFingerprint());
        assertEquals("9".repeat(64), identity.scheduleSemanticHash());
    }

    @Test
    void rejectsMalformedHash() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleFingerprint("org.example.ScheduleTest#case1", "not-a-hash"));
    }

    static CaseFingerprint sampleFingerprint(String selector, String inputHash) {
        return new CaseFingerprint(
                selector,
                "abc1234",
                "1".repeat(64),
                inputHash,
                "3".repeat(64),
                "21.0.8",
                "wafer-demo",
                "0.2.0");
    }
}
