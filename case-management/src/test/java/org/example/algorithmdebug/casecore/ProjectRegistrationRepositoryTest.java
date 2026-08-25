package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectRegistrationRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCreateAndReadRegistrationByIdAndList() throws Exception {
        WorkspaceLayout layout = layoutWithProjectsRoot();
        ProjectRegistrationRepository repository = repository();
        ProjectRegistration registration = registration(new ProjectId("algorithm-one-a1b2c3d4e5f6"));
        Files.createDirectories(layout.projectWorkspace(registration.projectId()));

        repository.create(layout, registration);

        assertEquals(registration, repository.findById(layout, registration.projectId()).orElseThrow());
        assertEquals(List.of(registration), repository.findAll(layout));
    }

    @Test
    void shouldAtomicallyReplaceExistingRegistration() throws Exception {
        WorkspaceLayout layout = layoutWithProjectsRoot();
        ProjectRegistrationRepository repository = repository();
        ProjectRegistration original = registration(new ProjectId("algorithm-one-a1b2c3d4e5f6"));
        Files.createDirectories(layout.projectWorkspace(original.projectId()));
        repository.create(layout, original);
        ProjectRegistration updated = new ProjectRegistration(
                original.schemaVersion(), original.projectId(), original.displayName(),
                original.repositoryRoot(), original.moduleRoot(), original.mavenExecutionRoot(),
                original.pomPath(), original.buildTool(),
                "output/results", original.registeredAt());

        repository.replace(layout, updated);

        assertEquals(updated, repository.findById(layout, original.projectId()).orElseThrow());
    }

    @Test
    void shouldReturnEmptyWhenProjectsOrRegistrationDoNotExist() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory.resolve("workspace"));
        ProjectRegistrationRepository repository = repository();

        assertEquals(List.of(), repository.findAll(layout));
        assertFalse(repository.findById(layout, new ProjectId("missing-project")).isPresent());
    }

    @Test
    void shouldRejectMalformedOrMisplacedRegistration() throws Exception {
        WorkspaceLayout layout = layoutWithProjectsRoot();
        Path malformedRoot = layout.projectWorkspace(new ProjectId("malformed-project"));
        Files.createDirectories(malformedRoot);
        Files.writeString(malformedRoot.resolve("project.json"), "{", StandardCharsets.UTF_8);

        WorkspaceException malformed = assertThrows(
                WorkspaceException.class,
                () -> repository().findAll(layout));

        assertEquals("PROJECT_REGISTRATION_INVALID", malformed.code());

        Files.delete(malformedRoot.resolve("project.json"));
        ProjectRegistration misplaced = registration(new ProjectId("different-project"));
        Files.write(malformedRoot.resolve("project.json"), new BoundedDocumentMapper().writeJson(misplaced));

        WorkspaceException mismatch = assertThrows(
                WorkspaceException.class,
                () -> repository().findAll(layout));
        assertEquals("PROJECT_REGISTRATION_INVALID", mismatch.code());
    }

    @Test
    void shouldClassifyInvalidProjectDirectoryNameAsInvalidRegistration() throws Exception {
        WorkspaceLayout layout = layoutWithProjectsRoot();
        Path invalidProject = Files.createDirectories(layout.projectsRoot().resolve("x".repeat(129)));
        Files.writeString(invalidProject.resolve("project.json"), "{}", StandardCharsets.UTF_8);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> repository().findAll(layout));

        assertEquals("PROJECT_REGISTRATION_INVALID", failure.code());
    }

    private WorkspaceLayout layoutWithProjectsRoot() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory.resolve("workspace"));
        Files.createDirectories(layout.projectsRoot());
        return layout;
    }

    private static ProjectRegistrationRepository repository() {
        return new ProjectRegistrationRepository(new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    private static ProjectRegistration registration(ProjectId projectId) {
        return new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION,
                projectId,
                "algorithm-one",
                "D:/large-system",
                "D:/large-system/algorithm-one",
                "D:/large-system/algorithm-one",
                "pom.xml",
                "MAVEN",
                null,
                Instant.parse("2026-08-16T00:00:00Z"));
    }
}
