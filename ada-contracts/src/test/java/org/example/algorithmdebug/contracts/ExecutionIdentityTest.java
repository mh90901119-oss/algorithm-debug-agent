package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证运行后身份只能在完整 Case 指纹之上追加合法的调度语义哈希。
 */
class ExecutionIdentityTest {

    @Test
    void bindsPreRunFingerprintToPostRunSemanticHash() {
        CaseFingerprint fingerprint = CaseFingerprintTest.sampleFingerprint(
                "org.example.ScheduleTest#case1", "1".repeat(64));

        ExecutionIdentity identity = new ExecutionIdentity(fingerprint, "9".repeat(64));

        assertEquals(fingerprint, identity.caseFingerprint());
        assertEquals("9".repeat(64), identity.scheduleSemanticHash());
    }

    @Test
    void rejectsMissingFingerprintAndMalformedSemanticHash() {
        CaseFingerprint fingerprint = CaseFingerprintTest.sampleFingerprint(
                "org.example.ScheduleTest#case1", "1".repeat(64));

        assertThrows(NullPointerException.class,
                () -> new ExecutionIdentity(null, "9".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionIdentity(fingerprint, "not-a-sha256"));
    }
}
