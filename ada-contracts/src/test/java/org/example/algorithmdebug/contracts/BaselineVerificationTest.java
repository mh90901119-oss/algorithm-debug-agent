package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaselineVerificationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void freezesRunObservationsAndStableState() {
        BaselineRunObservation first = new BaselineRunObservation(new RunId("RUN-001"), "a".repeat(64));
        BaselineRunObservation second = new BaselineRunObservation(new RunId("RUN-002"), "a".repeat(64));

        BaselineVerification verification = new BaselineVerification(
                "1.0",
                CaseFingerprintTest.sampleFingerprint("org.example.ScheduleTest#case1", "2".repeat(64)),
                "a".repeat(64),
                2,
                List.of(first, second),
                BaselineStabilityState.BASELINE_STABLE);

        assertEquals(2, verification.observations().size());
        assertThrows(UnsupportedOperationException.class,
                () -> verification.observations().add(first));
    }

    @Test
    void rejectsStableStateBelowRequiredMatchingRuns() {
        assertThrows(IllegalArgumentException.class, () -> new BaselineVerification(
                "1.0",
                CaseFingerprintTest.sampleFingerprint("org.example.ScheduleTest#case1", "2".repeat(64)),
                "a".repeat(64),
                2,
                List.of(new BaselineRunObservation(new RunId("RUN-001"), "a".repeat(64))),
                BaselineStabilityState.BASELINE_STABLE));
    }

    @Test
    void preservesBaselineV1JsonEnumValuesAfterJavaTypeSplit() throws Exception {
        String json = objectMapper.writeValueAsString(BaselineStabilityState.BASELINE_STABLE);

        assertEquals("\"BASELINE_STABLE\"", json);
        assertEquals(BaselineStabilityState.BASELINE_STABLE,
                objectMapper.readValue(json, BaselineStabilityState.class));
    }
}
