package org.example.algorithmdebug.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.casecore.logging.JavaExecutionLogRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaExecutionLoggingTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsStdoutAsOneToolResponseAndWritesCaseLog() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
        JavaExecutionLogRouter log = new JavaExecutionLogRouter(
                clock, Optional.of(temporaryDirectory.resolve("dfx")));
        AdaMain application = new AdaMain(
                command -> Map.of("status", "ok"), new CliResponseWriter(), log);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exit = application.run(new String[] {
                    "case", "inspect", "--workspace", temporaryDirectory.resolve("workspace").toString(),
                    "--project-id", "project-1", "--case-id", "case-1"
            }, out, err);
        }

        assertEquals(0, exit);
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(stdout.toString(StandardCharsets.UTF_8)).path("success").asBoolean());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        Path file = temporaryDirectory.resolve(
                "workspace/projects/project-1/cases/case-1/logs/agent-2026-08-28.log");
        String text = Files.readString(file);
        assertTrue(text.contains("CLI_INVOCATION_STARTED"));
        assertTrue(text.contains("CLI_INVOCATION_COMPLETED"));
        assertFalse(stdout.toString(StandardCharsets.UTF_8).contains("CLI_INVOCATION"));
    }

    @Test
    void writesUnexpectedFailureStackOnlyToBootstrapFile() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
        Path dfx = temporaryDirectory.resolve("dfx");
        AdaMain application = new AdaMain(
                command -> { throw new IllegalStateException("failed at D:/private/module"); },
                new CliResponseWriter(), new JavaExecutionLogRouter(clock, Optional.of(dfx)));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exit = application.run(new String[] {
                    "workspace", "init", "--root", temporaryDirectory.resolve("workspace").toString()
            }, out, err);
        }

        assertEquals(10, exit);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        String text = Files.readString(dfx.resolve("java/agent-bootstrap-2026-08-28.log"));
        assertTrue(text.contains("java.lang.IllegalStateException"));
        assertTrue(text.contains("<redacted-path>"));
        assertFalse(text.contains("private"));
    }
}
