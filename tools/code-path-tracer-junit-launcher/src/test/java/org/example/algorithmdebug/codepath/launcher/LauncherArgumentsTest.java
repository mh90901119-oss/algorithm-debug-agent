package org.example.algorithmdebug.codepath.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LauncherArgumentsTest {
    @Test
    void parsesExplicitRuntimeBudgets() {
        LauncherArguments arguments = LauncherArguments.parse(new String[] {
                "--test", "example.AlgorithmTest#runs",
                "--include", "example",
                "--trace", "trace.jsonl",
                "--max-output-bytes", "2048",
                "--max-events", "30"
        });

        assertEquals("example.AlgorithmTest#runs", arguments.testSelector());
        assertEquals("example", arguments.includePackage());
        assertEquals(Path.of("trace.jsonl"), arguments.traceFile());
        assertEquals(2048, arguments.maxOutputBytes());
        assertEquals(30, arguments.maxEvents());
    }

    @Test
    void rejectsUnknownDuplicateAndOverHardLimitArguments() {
        assertThrows(IllegalArgumentException.class, () -> LauncherArguments.parse(new String[] {
                "--test", "T#m", "--include", "example", "--trace", "x",
                "--max-output-bytes", "1", "--max-output-bytes", "2", "--max-events", "1"
        }));
        assertThrows(IllegalArgumentException.class, () -> LauncherArguments.parse(new String[] {
                "--test", "T#m", "--include", "example", "--trace", "x",
                "--max-output-bytes", Long.toString(TraceJsonlSink.HARD_MAX_OUTPUT_BYTES + 1),
                "--max-events", "1"
        }));
        assertThrows(IllegalArgumentException.class, () -> LauncherArguments.parse(new String[] {
                "--test", "T#m", "--include", "example", "--trace", "x",
                "--max-output-bytes", "1", "--max-events", "1", "--unknown", "x"
        }));
    }
}
