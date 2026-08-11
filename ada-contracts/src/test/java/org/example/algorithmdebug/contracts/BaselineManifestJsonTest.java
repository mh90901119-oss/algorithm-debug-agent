package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaselineManifestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void shouldRoundTripBaselineManifest() throws Exception {
        BaselineManifest manifest = sampleManifest();

        String json = objectMapper.writeValueAsString(manifest);
        BaselineManifest restored = objectMapper.readValue(json, BaselineManifest.class);

        assertEquals(manifest, restored);
    }

    @Test
    void shouldReadVersionTwoFixtureWhenFutureOptionalFieldExists() throws Exception {
        String json = objectMapper.writeValueAsString(sampleManifest());
        String futureJson = json.substring(0, json.length() - 1) + ",\"futureOptionalField\":true}";

        BaselineManifest restored = objectMapper.readValue(futureJson, BaselineManifest.class);

        assertEquals(new RunId("RUN-001"), restored.runId());
        assertEquals(SchemaVersions.BASELINE_MANIFEST, restored.schemaVersion());
    }

    private static BaselineManifest sampleManifest() {
        return new BaselineManifest(
                SchemaVersions.BASELINE_MANIFEST,
                new ProjectId("wafer-demo"),
                new CaseId("COMPLEX-PARALLEL-001"),
                new RunId("RUN-001"),
                Instant.parse("2026-08-10T08:00:00Z"),
                new TargetTest("org.example.ScheduleTest", "case1"),
                new ExecutionIdentity(
                        new CaseFingerprint(
                                "org.example.ScheduleTest#case1",
                                "abc1234",
                                "1".repeat(64),
                                "2".repeat(64),
                                "3".repeat(64),
                                "21.0.8",
                                "wafer-demo",
                                "0.2.0"),
                        "4".repeat(64)),
                new ArtifactReference(
                        "schedule-result",
                        "SCHEDULE_RESULT",
                        "result/schedule-result.json",
                        "application/json",
                        "5".repeat(64),
                        2048));
    }
}
