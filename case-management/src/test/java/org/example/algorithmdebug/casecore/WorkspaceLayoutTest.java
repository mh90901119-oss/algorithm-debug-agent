package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLayoutTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldDeriveStandardRootsFromNormalizedAbsoluteWorkspace() {
        Path requestedRoot = temporaryDirectory.resolve("parent/../agent-workspace");

        WorkspaceLayout layout = WorkspaceLayout.of(requestedRoot);

        assertTrue(layout.root().isAbsolute());
        assertEquals(temporaryDirectory.resolve("agent-workspace").toAbsolutePath().normalize(), layout.root());
        assertEquals(layout.root().resolve("config"), layout.configRoot());
        assertEquals(layout.root().resolve("projects"), layout.projectsRoot());
        assertEquals(layout.root().resolve("system"), layout.systemRoot());
    }

    @Test
    void shouldDeriveProjectCasesInsideExternalWorkspace() {
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory.resolve("agent-workspace"));

        Path cases = layout.projectCases(new ProjectId("algorithm-module-123"));

        assertEquals(layout.root().resolve("projects/algorithm-module-123/cases"), cases);
        assertTrue(cases.startsWith(layout.root()));
    }

    @Test
    void shouldRejectProjectIdsThatAreNotSingleSafePathSegments() {
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory.resolve("agent-workspace"));

        assertThrows(IllegalArgumentException.class,
                () -> layout.projectWorkspace(new ProjectId("../outside")));
        assertThrows(IllegalArgumentException.class,
                () -> layout.projectWorkspace(new ProjectId("nested/project")));
        assertThrows(IllegalArgumentException.class,
                () -> layout.projectWorkspace(new ProjectId("nested\\project")));
        assertThrows(IllegalArgumentException.class,
                () -> layout.projectWorkspace(new ProjectId("D:project")));
    }

    @Test
    void shouldRejectMissingWorkspaceRootOrProjectId() {
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory.resolve("agent-workspace"));

        assertThrows(IllegalArgumentException.class, () -> WorkspaceLayout.of(null));
        assertThrows(IllegalArgumentException.class, () -> layout.projectWorkspace(null));
    }

    @Test
    void shouldRejectFilesystemRootAsWorkspace() {
        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> WorkspaceLayout.of(temporaryDirectory.getRoot()));

        assertEquals("WORKSPACE_PATH_INVALID", failure.code());
    }
}
