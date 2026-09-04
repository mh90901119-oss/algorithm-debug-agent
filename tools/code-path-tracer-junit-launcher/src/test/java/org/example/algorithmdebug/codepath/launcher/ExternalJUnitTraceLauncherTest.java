package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalJUnitTraceLauncherTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stopsCaptureAfterTheTraceSinkFails() throws Exception {
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicBoolean stopped = new AtomicBoolean();
        TraceJsonlSink sink = new TraceJsonlSink(
                temporaryDirectory.resolve("trace.jsonl"), 1024, 10);
        sink.close();

        ExternalJUnitTraceLauncher.appendTraceLine(sink, "{}", failure, stopped);

        assertNotNull(failure.get());
        assertTrue(stopped.get());
    }
}
