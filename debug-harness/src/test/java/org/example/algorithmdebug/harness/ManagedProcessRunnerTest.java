package org.example.algorithmdebug.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedProcessRunnerTest {

    @TempDir
    Path directory;

    @Test
    void startsWithoutBlockingAndObservesMarkerAcrossOutputChunks() throws Exception {
        ManagedProcess process = new ManagedProcessRunner().start(
                fixture("marker", "JDWP-READY"), directory,
                directory.resolve("stdout.log"), directory.resolve("stderr.log"),
                ProcessLimits.defaults(), List.of("JDWP-READY"));
        try {
            assertTrue(process.isAlive());
            assertEquals(ProcessOutputWaitResult.OBSERVED,
                    process.awaitOutput("JDWP-READY", Duration.ofSeconds(5)));
        } finally {
            process.close();
        }
        assertFalse(process.isAlive());
    }

    @Test
    void distinguishesEarlyExitFromMarkerTimeout() throws Exception {
        try (ManagedProcess exited = new ManagedProcessRunner().start(
                fixture("exit", "0"), directory,
                directory.resolve("exit-out.log"), directory.resolve("exit-err.log"),
                ProcessLimits.defaults(), List.of("never"))) {
            assertEquals(ProcessOutputWaitResult.PROCESS_EXITED,
                    exited.awaitOutput("never", Duration.ofSeconds(5)));
        }
        try (ManagedProcess sleeping = new ManagedProcessRunner().start(
                fixture("sleep"), directory,
                directory.resolve("sleep-out.log"), directory.resolve("sleep-err.log"),
                ProcessLimits.defaults(), List.of("never"))) {
            assertEquals(ProcessOutputWaitResult.TIMED_OUT,
                    sleeping.awaitOutput("never", Duration.ofMillis(50)));
        }
    }

    @Test
    void drainsExitedProcessOutputBeforeDeclaringMarkerMissing() throws Exception {
        try (ManagedProcess process = new ManagedProcessRunner().start(
                fixture("marker-exit", "FINAL-MARKER"), directory,
                directory.resolve("marker-exit-out.log"),
                directory.resolve("marker-exit-err.log"),
                ProcessLimits.defaults(), List.of("FINAL-MARKER"))) {
            assertEquals(ProcessOutputWaitResult.OBSERVED,
                    process.awaitOutput("FINAL-MARKER", Duration.ofSeconds(5)));
        }
    }

    @Test
    void awaitTimeoutAndRepeatedCloseLeaveNoProcessAlive() throws Exception {
        ManagedProcess process = new ManagedProcessRunner().start(
                fixture("spawn-child"), directory,
                directory.resolve("tree-out.log"), directory.resolve("tree-err.log"),
                ProcessLimits.defaults(), List.of());

        RunResult result = process.await(Duration.ofMillis(100));
        process.close();
        process.close();

        assertEquals(RunCompletion.TIMED_OUT, result.completion());
        assertTrue(result.termination().attempted());
        assertTrue(result.termination().survivingProcessIds().isEmpty());
        assertFalse(process.isAlive());
        assertTrue(Files.isRegularFile(result.stdout().path()));
        assertTrue(Files.isRegularFile(result.stderr().path()));
    }

    @Test
    void rejectsInvalidMarkersBeforeCreatingLogsOrStartingProcess() {
        Path stdout = directory.resolve("invalid-out.log");
        Path stderr = directory.resolve("invalid-err.log");

        assertThrows(IllegalArgumentException.class, () -> new ManagedProcessRunner().start(
                fixture("exit", "0"), directory, stdout, stderr,
                ProcessLimits.defaults(), List.of("duplicate", "duplicate")));

        assertFalse(Files.exists(stdout));
        assertFalse(Files.exists(stderr));
    }

    @Test
    void rejectsSharedLogPathBeforeCreatingIt() {
        Path shared = directory.resolve("shared.log");

        assertThrows(IllegalArgumentException.class, () -> new ManagedProcessRunner().start(
                fixture("exit", "0"), directory, shared, shared,
                ProcessLimits.defaults(), List.of()));

        assertFalse(Files.exists(shared));
    }

    private static List<String> fixture(String... arguments) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ProcessFixtureMain.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }
}
