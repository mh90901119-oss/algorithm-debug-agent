package org.example.algorithmdebug.casecore.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaExecutionLogRouterTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-28T02:03:04Z"), ZoneId.of("Asia/Shanghai"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesEnglishRedactedCaseLogAndAppendsWithoutOverwriting() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        AgentLogContext context = AgentLogContext.forCase(
                workspace, new ProjectId("project-1"), new CaseId("case-1"))
                .withAnalysis(new org.example.algorithmdebug.contracts.AnalysisId("analysis-1"));
        JavaExecutionLogRouter log = new JavaExecutionLogRouter(
                CLOCK, Optional.of(temporaryDirectory.resolve("dfx")));

        log.info(context, "RunApplicationService", "RUN_EXECUTION_STARTED", "STARTED",
                "Target test execution started", Map.of("command", "run-test"));
        log.error(context, "AdaMain", "CLI_INVOCATION_FAILED", "FAILED",
                "Unexpected failure at D:/sensitive/target/input.json with token=abc",
                Map.of(), new IllegalStateException("根因 D:/private/data.json",
                        new IllegalArgumentException("cause")));

        Path file = workspace.resolve(
                "projects/project-1/cases/case-1/logs/agent-2026-08-28.log");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("RUN_EXECUTION_STARTED"));
        assertTrue(text.contains("CLI_INVOCATION_FAILED"));
        assertTrue(text.contains("IllegalStateException"));
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: cause"));
        assertTrue(text.contains("<redacted-path>"));
        assertTrue(text.contains("token=<redacted>"));
        assertFalse(text.contains("sensitive"));
        assertFalse(text.contains("private"));
        assertFalse(text.codePoints().anyMatch(codePoint -> codePoint > 127));
        assertEquals(2, text.lines().filter(line -> !line.startsWith("STACK ")).count());
    }

    @Test
    void writesOnlyErrorsWithoutCaseToBootstrapLog() throws Exception {
        Path dfx = temporaryDirectory.resolve("diagnostics");
        JavaExecutionLogRouter log = new JavaExecutionLogRouter(CLOCK, Optional.of(dfx));

        log.info(AgentLogContext.bootstrap(), "AdaMain", "CLI_INVOCATION_STARTED",
                "STARTED", "CLI invocation started");
        assertFalse(Files.exists(dfx));

        log.error(AgentLogContext.bootstrap(), "AdaMain", "CLI_INVOCATION_FAILED",
                "FAILED", "CLI invocation failed", Map.of(), new IllegalStateException("boom"));

        Path file = dfx.resolve("java/agent-bootstrap-2026-08-28.log");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.readString(file).contains("IllegalStateException: boom"));
    }

    @Test
    void loggingFailureNeverEscapesOrCreatesAnAlternativeDirectory() throws Exception {
        Path blocked = temporaryDirectory.resolve("blocked");
        Files.writeString(blocked, "not-a-directory");
        JavaExecutionLogRouter log = new JavaExecutionLogRouter(CLOCK, Optional.of(blocked));

        assertDoesNotThrow(() -> log.error(
                AgentLogContext.bootstrap(), "AdaMain", "CLI_INVOCATION_FAILED", "FAILED",
                "CLI invocation failed", Map.of(), new IllegalStateException("boom")));
        assertTrue(Files.isRegularFile(blocked));
        assertFalse(Files.exists(temporaryDirectory.resolve("java")));
    }

    @Test
    void resolvesCasePathInsideTheExpectedCaseOnly() {
        AgentLogContext context = AgentLogContext.forCase(
                temporaryDirectory.resolve("workspace"),
                new ProjectId("project-1"), new CaseId("case-1"));

        Path resolved = new CaseLogPathResolver().caseLog(
                context, java.time.LocalDate.parse("2026-08-28"));

        assertTrue(resolved.startsWith(temporaryDirectory.resolve(
                "workspace/projects/project-1/cases/case-1").toAbsolutePath()));
        assertTrue(resolved.endsWith("logs/agent-2026-08-28.log"));
    }
}
