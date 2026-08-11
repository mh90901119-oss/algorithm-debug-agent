package org.example.algorithmdebug.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSupervisorTest {

    @Test
    void shouldReturnNonZeroExitWithoutCleanup() throws Exception {
        Process process = fixture("exit", "7").start();

        SupervisionResult result = new ProcessSupervisor().await(
                process, Duration.ofSeconds(5), ProcessLimits.defaults());

        assertFalse(result.timedOut());
        assertEquals(OptionalInt.of(7), result.exitCode());
        assertFalse(result.termination().attempted());
    }

    @Test
    void shouldTerminateRootAndChildProcessOnTimeout() throws Exception {
        Process process = fixture("spawn-child").start();

        SupervisionResult result = new ProcessSupervisor().await(
                process,
                Duration.ofMillis(500),
                new ProcessLimits(1024, 1024, Duration.ofMillis(500), Duration.ofSeconds(2)));

        assertTrue(result.timedOut());
        assertTrue(result.termination().attempted());
        assertTrue(result.termination().gracefulSignals() > 0);
        assertTrue(result.termination().survivingProcessIds().isEmpty());
        assertFalse(process.isAlive());
    }

    private static ProcessBuilder fixture(String... arguments) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ProcessFixtureMain.class.getName());
        command.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(command);
    }
}
