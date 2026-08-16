package org.example.algorithmdebug.cli;

import org.example.algorithmdebug.contracts.ProjectId;
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
    void shouldRejectUnknownCommandsOptionsDuplicatesAndExtraArguments() {
        List<String[]> invalid = List.of(
                new String[]{},
                new String[]{"unknown"},
                new String[]{"workspace", "unknown", "--root", "x"},
                new String[]{"workspace", "init", "--unknown", "x"},
                new String[]{"workspace", "init", "--root", "x", "--root", "y"},
                new String[]{"workspace", "init", "--root", "x", "extra"},
                new String[]{"doctor", "--workspace", "x", "--project-id", "id"});

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
