package org.example.algorithmdebug.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunResultTest {

    private static final Instant STARTED = Instant.parse("2026-08-11T00:00:00Z");
    private static final Instant FINISHED = STARTED.plusSeconds(1);

    @Test
    void shouldEnforceExitCodeAndTerminationInvariants() {
        assertDoesNotThrow(() -> result(
                RunCompletion.SUCCEEDED,
                OptionalInt.of(0),
                TerminationReport.notAttempted()));
        assertDoesNotThrow(() -> result(
                RunCompletion.FAILED,
                OptionalInt.of(1),
                TerminationReport.notAttempted()));
        assertDoesNotThrow(() -> result(
                RunCompletion.TIMED_OUT,
                OptionalInt.empty(),
                new TerminationReport(true, 1, 1, List.of())));

        assertThrows(IllegalArgumentException.class, () -> result(
                RunCompletion.SUCCEEDED,
                OptionalInt.of(2),
                TerminationReport.notAttempted()));
        assertThrows(IllegalArgumentException.class, () -> result(
                RunCompletion.FAILED,
                OptionalInt.of(0),
                TerminationReport.notAttempted()));
        assertThrows(IllegalArgumentException.class, () -> result(
                RunCompletion.TIMED_OUT,
                OptionalInt.empty(),
                TerminationReport.notAttempted()));
    }

    private static RunResult result(
            RunCompletion completion,
            OptionalInt exitCode,
            TerminationReport termination) {
        return new RunResult(
                completion,
                exitCode,
                STARTED,
                FINISHED,
                Duration.ofSeconds(1),
                42L,
                new RunLog(Path.of("stdout.log").toAbsolutePath(), 0, 0, false),
                new RunLog(Path.of("stderr.log").toAbsolutePath(), 0, 0, false),
                termination);
    }
}
