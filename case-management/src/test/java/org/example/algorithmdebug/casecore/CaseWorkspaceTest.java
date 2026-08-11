package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ArtifactReference;
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
    void createsCaseHierarchyAndImmutableRunArtifact() throws Exception {
        CaseWorkspace workspace = CaseWorkspace.create(temporaryDirectory, new CaseId("CASE-001"));
        Path run = workspace.createRun(new RunId("RUN-001"));
        Path source = temporaryDirectory.resolve("source.json");
        Files.writeString(source, "{\"makespan\":13}");
        ImmutableArtifactStore store = new ImmutableArtifactStore();

        ArtifactReference reference = store.copy(
                source,
                run,
                Path.of("result", "gantt.json"),
                "schedule-result",
                "SCHEDULE_RESULT",
                "application/json");

        assertTrue(Files.isDirectory(workspace.caseRoot().resolve("baseline")));
        assertTrue(Files.isDirectory(workspace.caseRoot().resolve("inquiries")));
        assertTrue(Files.isRegularFile(run.resolve("result/gantt.json")));
        assertEquals("result/gantt.json", reference.relativePath());
        assertThrows(FileAlreadyExistsException.class, () -> store.copy(
                source,
                run,
                Path.of("result", "gantt.json"),
                "schedule-result-2",
                "SCHEDULE_RESULT",
                "application/json"));
    }
}
