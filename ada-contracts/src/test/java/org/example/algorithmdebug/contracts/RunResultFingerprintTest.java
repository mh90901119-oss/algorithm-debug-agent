package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunResultFingerprintTest {

    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final RunId RUN_ID = new RunId("run-1");
    private static final String RAW_HASH = "a".repeat(64);
    private static final String NORMALIZED_HASH = "b".repeat(64);
    private static final String FAILURE_HASH = "c".repeat(64);

    @Test
    void acceptsSuccessfulGanttObservation() {
        RunResultFingerprint fingerprint = fingerprint(
                Optional.of(RAW_HASH), Optional.of(NORMALIZED_HASH), Optional.empty());

        assertEquals(Optional.of(RAW_HASH), fingerprint.ganttRawSha256());
        assertEquals(Optional.of(NORMALIZED_HASH), fingerprint.ganttNormalizedJsonSha256());
        assertEquals(Optional.empty(), fingerprint.targetFailureSha256());
    }

    @Test
    void acceptsFailureOnlyAndAssertionFailureWithGantt() {
        RunResultFingerprint failureOnly = fingerprint(
                Optional.empty(), Optional.empty(), Optional.of(FAILURE_HASH));
        RunResultFingerprint assertionWithGantt = fingerprint(
                Optional.of(RAW_HASH), Optional.of(NORMALIZED_HASH), Optional.of(FAILURE_HASH));

        assertEquals(Optional.of(FAILURE_HASH), failureOnly.targetFailureSha256());
        assertEquals(Optional.of(FAILURE_HASH), assertionWithGantt.targetFailureSha256());
        assertEquals(Optional.of(NORMALIZED_HASH),
                assertionWithGantt.ganttNormalizedJsonSha256());
    }

    @Test
    void normalizesUppercaseHashesToLowercase() {
        RunResultFingerprint fingerprint = fingerprint(
                Optional.of("A".repeat(64)),
                Optional.of("B".repeat(64)),
                Optional.of("C".repeat(64)));

        assertEquals(Optional.of(RAW_HASH), fingerprint.ganttRawSha256());
        assertEquals(Optional.of(NORMALIZED_HASH), fingerprint.ganttNormalizedJsonSha256());
        assertEquals(Optional.of(FAILURE_HASH), fingerprint.targetFailureSha256());
    }

    @Test
    void rejectsUnpairedGanttHashes() {
        assertThrows(IllegalArgumentException.class, () -> fingerprint(
                Optional.of(RAW_HASH), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> fingerprint(
                Optional.empty(), Optional.of(NORMALIZED_HASH), Optional.empty()));
    }

    @Test
    void rejectsFingerprintWithoutTargetObservation() {
        assertThrows(IllegalArgumentException.class, () -> fingerprint(
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void rejectsUnsupportedVersionAndInvalidHash() {
        assertThrows(IllegalArgumentException.class, () -> new RunResultFingerprint(
                "2.0", CASE_ID, CONTEXT_ID, RUN_ID,
                Optional.of(RAW_HASH), Optional.of(NORMALIZED_HASH), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> fingerprint(
                Optional.of("not-a-hash"), Optional.of(NORMALIZED_HASH), Optional.empty()));
    }

    private static RunResultFingerprint fingerprint(
            Optional<String> raw,
            Optional<String> normalized,
            Optional<String> failure) {
        return new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT,
                CASE_ID,
                CONTEXT_ID,
                RUN_ID,
                raw,
                normalized,
                failure);
    }
}
