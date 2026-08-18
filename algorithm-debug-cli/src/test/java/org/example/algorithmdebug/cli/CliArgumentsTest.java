package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.PlanId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliArgumentsTest {

    @Test
    void shouldParseAllSupportedCommandsExactly() {
        assertEquals(
                new CliCommand.WorkspaceInit(Path.of("D:/agent-workspace")),
                CliArguments.parse(new String[]{"workspace", "init", "--root", "D:/agent-workspace"}));
        assertEquals(
                new CliCommand.ProjectRegister(
                        Path.of("D:/agent-workspace"),
                        Path.of("D:/large-system/algorithm-module"),
                        Optional.of(new ProjectId("algorithm-module-123"))),
                CliArguments.parse(new String[]{
                        "project", "register",
                        "--project", "D:/large-system/algorithm-module",
                        "--project-id", "algorithm-module-123",
                        "--workspace", "D:/agent-workspace"}));
        assertEquals(
                new CliCommand.Doctor(
                        Path.of("D:/agent-workspace"),
                        Optional.of(Path.of("D:/large-system/algorithm-module"))),
                CliArguments.parse(new String[]{
                        "doctor", "--project", "D:/large-system/algorithm-module",
                        "--workspace", "D:/agent-workspace"}));
    }

    @Test
    void shouldParseOptionalArgumentsAsEmpty() {
        assertEquals(
                new CliCommand.ProjectRegister(
                        Path.of("workspace"), Path.of("module"), Optional.empty()),
                CliArguments.parse(new String[]{
                        "project", "register", "--workspace", "workspace", "--project", "module"}));
        assertEquals(
                new CliCommand.Doctor(Path.of("workspace"), Optional.empty()),
                CliArguments.parse(new String[]{"doctor", "--workspace", "workspace"}));
    }

    @Test
    void shouldParseCaseAndRunCommands() {
        assertEquals(
                new CliCommand.CaseOpen(
                        Path.of("workspace"), new ProjectId("demo"),
                        new TargetTest("a.b.Test", "case1"), Path.of("question.txt"),
                        Optional.empty(), Optional.empty()),
                CliArguments.parse(new String[]{
                        "case", "open", "--workspace", "workspace",
                        "--project-id", "demo", "--test", "a.b.Test#case1",
                        "--question-file", "question.txt"}));
        assertEquals(
                new CliCommand.CaseInspect(
                        Path.of("workspace"), new ProjectId("demo"), new CaseId("case-1")),
                CliArguments.parse(new String[]{
                        "case", "inspect", "--workspace", "workspace",
                        "--project-id", "demo", "--case-id", "case-1"}));
        assertEquals(
                new CliCommand.RunExecute(
                        Path.of("workspace"), new ProjectId("demo"),
                        new CaseId("case-1"), new AnalysisId("analysis-1")),
                CliArguments.parse(new String[]{
                        "run", "execute", "--workspace", "workspace",
                        "--project-id", "demo", "--case-id", "case-1",
                        "--analysis-id", "analysis-1"}));
        assertEquals(
                new CliCommand.StaticAnalyze(Path.of("workspace"), new ProjectId("demo"),
                        new CaseId("case-1"), new AnalysisId("analysis-1")),
                CliArguments.parse(new String[]{
                        "static", "analyze", "--workspace", "workspace",
                        "--project-id", "demo", "--case-id", "case-1",
                        "--analysis-id", "analysis-1"}));
        assertEquals(
                new CliCommand.CodePathPlanCreate(Path.of("workspace"), new ProjectId("demo"),
                        new CaseId("case-1"), new AnalysisId("analysis-1"), Path.of("plan.json")),
                CliArguments.parse(new String[]{
                        "plan", "codepath", "create", "--workspace", "workspace",
                        "--project-id", "demo", "--case-id", "case-1",
                        "--analysis-id", "analysis-1", "--request-file", "plan.json"}));
        assertEquals(
                new CliCommand.CodePathCollectionExecute(
                        Path.of("workspace"), new ProjectId("demo"), new CaseId("case-1"),
                        new PlanId("plan-1")),
                CliArguments.parse(new String[]{
                        "collection", "codepath", "execute", "--workspace", "workspace",
                        "--project-id", "demo", "--case-id", "case-1", "--plan-id", "plan-1"}));
        assertEquals(
                new CliCommand.JdwpPlanCreate(Path.of("workspace"), new ProjectId("demo"),
                        new CaseId("case-1"), new AnalysisId("analysis-1"), Path.of("jdwp.json")),
                CliArguments.parse(new String[]{
                        "plan", "jdwp", "create", "--workspace", "workspace",
                        "--project-id", "demo", "--case-id", "case-1",
                        "--analysis-id", "analysis-1", "--request-file", "jdwp.json"}));
        assertEquals(
                new CliCommand.JdwpCollectionExecute(
                        Path.of("workspace"), new ProjectId("demo"), new CaseId("case-1"),
                        new PlanId("jdwp-plan-1")),
                CliArguments.parse(new String[]{
                        "collection", "jdwp", "execute", "--workspace", "workspace",
                        "--project-id", "demo", "--case-id", "case-1",
                        "--plan-id", "jdwp-plan-1"}));
    }

    @Test
    void shouldRejectUnknownCommandsOptionsDuplicatesAndExtraArguments() {
        List<String[]> invalid = List.of(
                new String[]{},
                new String[]{"unknown"},
                new String[]{"workspace", "unknown", "--root", "x"},
                new String[]{"workspace", "init", "--unknown", "x"},
                new String[]{"workspace", "init", "--root", "x", "--root", "y"},
                new String[]{"workspace", "init", "--root", "x", "extra"},
                new String[]{"doctor", "--workspace", "x", "--project-id", "id"},
                new String[]{"case", "open", "--workspace", "w", "--project-id", "p",
                        "--test", "missing-separator", "--question-file", "q"},
                new String[]{"run", "execute", "--workspace", "w", "--project-id", "p",
                        "--case-id", "c"});

        for (String[] arguments : invalid) {
            assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(arguments));
        }
    }

    @Test
    void shouldRejectMissingAndEmptyOptionValues() {
        List<String[]> invalid = List.of(
                new String[]{"workspace", "init"},
                new String[]{"workspace", "init", "--root"},
                new String[]{"workspace", "init", "--root", ""},
                new String[]{"workspace", "init", "--root", "--other"},
                new String[]{"project", "register", "--workspace", "x"},
                new String[]{"doctor", "--workspace", ""});

        for (String[] arguments : invalid) {
            assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(arguments));
        }
    }
}
