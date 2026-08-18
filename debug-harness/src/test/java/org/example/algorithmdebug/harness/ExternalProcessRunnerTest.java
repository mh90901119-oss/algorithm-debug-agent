package org.example.algorithmdebug.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalProcessRunnerTest {
    @TempDir Path directory;

    @Test
    void executesArgvAndArchivesBothLogs() throws Exception {
        List<String> argv = fixture("exit", "7");
        RunResult result = new ExternalProcessRunner().execute(
                argv, directory, directory.resolve("stdout.log"), directory.resolve("stderr.log"),
                Duration.ofSeconds(5), ProcessLimits.defaults());
        assertEquals(RunCompletion.FAILED, result.completion());
        assertEquals(7, result.exitCode().orElseThrow());
        assertFalse(result.termination().attempted());
    }

    private static List<String> fixture(String... arguments) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp"); command.add(System.getProperty("java.class.path"));
        command.add(ProcessFixtureMain.class.getName()); command.addAll(List.of(arguments));
        return command;
    }
}
