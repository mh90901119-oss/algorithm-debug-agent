package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedDocumentMapperTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesContractTimesAsIso8601Strings() {
        byte[] json = new BoundedDocumentMapper().writeJson(
                new TimestampFixture(Instant.parse("2026-08-18T00:00:00Z")));

        assertEquals("{\"createdAt\":\"2026-08-18T00:00:00Z\"}",
                new String(json, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsJsonArtifactAboveOneHundredTwentyEightMebibytesBeforeParsing() throws Exception {
        long maximum = 128L * 1024 * 1024;
        Path oversized = temporaryDirectory.resolve("oversized.json");
        try (FileChannel channel = FileChannel.open(
                oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(maximum);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }

        WorkspaceException failure = assertThrows(WorkspaceException.class, () ->
                new BoundedDocumentMapper().readJsonArtifact(oversized, Object.class));
        assertTrue(failure.getMessage().contains("exceeds maximum byte count " + maximum));
    }

    private record TimestampFixture(Instant createdAt) {
    }
}
