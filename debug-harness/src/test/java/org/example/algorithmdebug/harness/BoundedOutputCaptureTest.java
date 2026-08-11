package org.example.algorithmdebug.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedOutputCaptureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldArchiveOnlyBudgetAndContinueDrainingInput() throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(content);
        Path log = temporaryDirectory.resolve("stdout.log");

        RunLog result = new BoundedOutputCapture().capture(input, log, 4);

        assertEquals("0123", Files.readString(log));
        assertEquals(4, result.capturedBytes());
        assertEquals(6, result.discardedBytes());
        assertTrue(result.truncated());
        assertEquals(0, input.available());
    }

    @Test
    void shouldRefuseToOverwriteExistingLog() throws Exception {
        Path log = temporaryDirectory.resolve("stderr.log");
        Files.writeString(log, "existing");

        HarnessException exception = assertThrows(HarnessException.class,
                () -> new BoundedOutputCapture().capture(
                        new ByteArrayInputStream(new byte[0]), log, 10));

        assertEquals("HARNESS_LOG_OPEN_FAILED", exception.code());
        assertEquals("existing", Files.readString(log));
    }
}
