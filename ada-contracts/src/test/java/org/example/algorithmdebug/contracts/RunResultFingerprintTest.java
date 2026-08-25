package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunResultFingerprintTest {
    @Test
    void recordsOnlyTheStructuredTargetFailureFingerprint() {
        RunResultFingerprint value = new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, new CaseId("case-1"),
                new ContextId("context-1"), new RunId("run-1"), "a".repeat(64));
        assertEquals("a".repeat(64), value.targetFailureSha256());
    }

    @Test
    void rejectsInvalidFailureFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, new CaseId("case-1"),
                new ContextId("context-1"), new RunId("run-1"), "not-a-hash"));
    }
}
