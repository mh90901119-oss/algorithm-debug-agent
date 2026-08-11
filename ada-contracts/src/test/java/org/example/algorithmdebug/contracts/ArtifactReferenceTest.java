package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactReferenceTest {

    private static final String SHA256 = "a".repeat(64);

    @Test
    void shouldKeepPortableRelativePathAndNormalizeHashCase() {
        ArtifactReference reference = new ArtifactReference(
                "schedule-result",
                "SCHEDULE_RESULT",
                "result/schedule-result.json",
                "application/json",
                SHA256.toUpperCase(),
                1024);

        assertEquals("result/schedule-result.json", reference.relativePath());
        assertEquals(SHA256, reference.sha256());
    }

    @Test
    void shouldRejectAbsoluteTraversalAndPlatformSpecificPaths() {
        assertThrows(IllegalArgumentException.class, () -> artifact("/result/data.json"));
        assertThrows(IllegalArgumentException.class, () -> artifact("../result/data.json"));
        assertThrows(IllegalArgumentException.class, () -> artifact("result\\data.json"));
        assertThrows(IllegalArgumentException.class, () -> artifact("C:/result/data.json"));
    }

    @Test
    void shouldRejectInvalidHashAndNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactReference(
                "id", "TRACE", "trace/raw.jsonl", "application/x-ndjson", "bad", 1));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactReference(
                "id", "TRACE", "trace/raw.jsonl", "application/x-ndjson", SHA256, -1));
    }

    private static ArtifactReference artifact(String path) {
        return new ArtifactReference("id", "TRACE", path, "application/json", SHA256, 1);
    }
}

