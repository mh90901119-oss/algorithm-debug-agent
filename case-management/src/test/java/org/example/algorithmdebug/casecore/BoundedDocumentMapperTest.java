package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedDocumentMapperTest {

    @TempDir
    Path temporaryDirectory;

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

        assertTrue(failure.getMessage().contains("超过最大字节数 " + maximum));
    }
}
