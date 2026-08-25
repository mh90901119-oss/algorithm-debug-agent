package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.example.algorithmdebug.contracts.CaseArtifactRegistration;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GanttArtifactInspectorTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final CaseId CASE_ID = new CaseId("case-1");

    @TempDir
    Path temporaryDirectory;
    private Path workspace;
    private Path gantt;

    @BeforeEach
    void setUp() throws Exception {
        workspace = temporaryDirectory.resolve("workspace");
        Path casesRoot = workspace.resolve("projects/project-1/cases");
        Path caseRoot = Files.createDirectories(casesRoot.resolve("case-1"));
        gantt = caseRoot.resolve("runs/run-1/raw/gantt.json");
        Files.createDirectories(gantt.getParent());
        Files.writeString(gantt, """
                {"tasks":[{"id":1},{"id":2},{"id":3}],"meta":{"stationCount":2}}
                """);
        var reference = new CaseArtifactAccess(casesRoot).describe(
                CASE_ID, "gantt-1", "GANTT", "application/json", gantt);
        var registration = new CaseArtifactRegistration(
                SchemaVersions.CASE_ARTIFACT_REGISTRATION, CASE_ID, reference, Instant.EPOCH);
        Path registrations = Files.createDirectory(caseRoot.resolve("artifacts"));
        Files.write(registrations.resolve("gantt-1.json"),
                new BoundedDocumentMapper().writeJson(registration));
    }

    @Test
    void returnsBoundedStructureSummaryAndJsonPointerSlice() throws Exception {
        GanttArtifactInspector inspector = new GanttArtifactInspector();

        var summary = inspector.inspect(
                workspace, PROJECT_ID, CASE_ID, "gantt-1", "summary", "", 0, 20);
        var slice = inspector.inspect(
                workspace, PROJECT_ID, CASE_ID, "gantt-1", "slice", "/tasks", 1, 1);

        assertEquals("OBJECT", summary.nodeType());
        assertEquals(2, summary.totalItems());
        assertTrue(summary.fields().containsAll(java.util.List.of("tasks", "meta")));
        assertEquals(3, slice.totalItems());
        assertEquals(1, slice.returnedItems());
        assertTrue(slice.truncated());
        assertEquals("[{\"id\":2}]", slice.json());
    }

    @Test
    void rejectsArtifactChangedAfterRegistration() throws Exception {
        Files.writeString(gantt, "{\"tasks\":[]}");

        WorkspaceException failure = assertThrows(WorkspaceException.class, () ->
                new GanttArtifactInspector().inspect(
                        workspace, PROJECT_ID, CASE_ID, "gantt-1", "summary", "", 0, 20));

        assertEquals("CASE_ARTIFACT_INTEGRITY_MISMATCH", failure.code());
    }
}
