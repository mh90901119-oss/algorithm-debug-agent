package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseWorkspaceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsOnlyDirectoriesThatContainCaseData() throws Exception {
        CaseWorkspace workspace = CaseWorkspace.create(temporaryDirectory, new CaseId("CASE-001"));

        assertTrue(Files.isDirectory(workspace.caseRoot()));
        try (var entries = Files.list(workspace.caseRoot())) {
            assertEquals(0, entries.count());
        }

        Path run = workspace.createRun(new RunId("RUN-001"));

        assertTrue(Files.notExists(workspace.caseRoot().resolve("baseline")));
        assertTrue(Files.notExists(workspace.caseRoot().resolve("contexts")));
        assertTrue(Files.notExists(workspace.caseRoot().resolve("analyses")));
        assertTrue(Files.notExists(workspace.caseRoot().resolve("evidence")));
        assertTrue(Files.notExists(workspace.caseRoot().resolve("inquiries")));
        assertTrue(Files.isDirectory(run));
        assertThrows(FileAlreadyExistsException.class,
                () -> workspace.createRun(new RunId("RUN-001")));
    }
}