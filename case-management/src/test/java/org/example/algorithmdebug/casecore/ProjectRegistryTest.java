package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistrationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRegistryTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldKeepRootsDistinctAndNeverModifyTargetRepository() throws Exception {
        Path workspace = initializeWorkspace();
        Path repository = createRepository();
        Path module = createModule(repository, "modules/algorithm-scheduler", "<project><name>demo</name></project>");
        Map<String, String> targetBefore = snapshot(repository);

        ProjectRegistrationResult result = registry().register(workspace, module, Optional.empty());

        assertTrue(result.created());
        assertEquals(portable(repository), result.registration().repositoryRoot());
        assertEquals(portable(module), result.registration().moduleRoot());
        assertEquals(portable(module), result.registration().mavenExecutionRoot());
        assertEquals("pom.xml", result.registration().pomPath());
        assertEquals("MAVEN", result.registration().buildTool());
        assertEquals(sha256(module.resolve("pom.xml")), result.registration().pomSha256());
        assertEquals(REGISTERED_AT, result.registration().registeredAt());
        assertEquals(targetBefore, snapshot(repository));

        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        Path projectRoot = layout.projectWorkspace(result.registration().projectId());
        assertTrue(Files.isRegularFile(projectRoot.resolve("project.json")));
        assertTrue(Files.isDirectory(projectRoot.resolve("knowledge/sources")));
        assertTrue(Files.isDirectory(projectRoot.resolve("knowledge/manifests")));
        assertTrue(Files.isDirectory(projectRoot.resolve("knowledge/indexes")));
        assertTrue(Files.isDirectory(projectRoot.resolve("cases")));
    }

    @Test
    void shouldReturnExistingRegistrationForIdenticalRequest() throws Exception {
        Path workspace = initializeWorkspace();
        Path module = createModule(createRepository(), "algorithm-scheduler", "<project/>");
        ProjectRegistry registry = registry();

        ProjectRegistrationResult first = registry.register(workspace, module, Optional.empty());
        ProjectRegistrationResult second = registry.register(workspace, module, Optional.empty());

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.registration(), second.registration());
    }

    @Test
    void shouldRejectIdAndModulePathConflictsDeterministically() throws Exception {
        Path workspace = initializeWorkspace();
        Path repository = createRepository();
        Path firstModule = createModule(repository, "algorithm-one", "<project/>");
        Path secondModule = createModule(repository, "algorithm-two", "<project/>");
        ProjectRegistry registry = registry();
        ProjectId firstId = new ProjectId("explicit-one");
        registry.register(workspace, firstModule, Optional.of(firstId));

        WorkspaceException idConflict = assertThrows(
                WorkspaceException.class,
                () -> registry.register(workspace, secondModule, Optional.of(firstId)));
        WorkspaceException pathConflict = assertThrows(
                WorkspaceException.class,
                () -> registry.register(workspace, firstModule, Optional.of(new ProjectId("explicit-two"))));

        assertEquals("PROJECT_ID_CONFLICT", idConflict.code());
        assertEquals("PROJECT_PATH_CONFLICT", pathConflict.code());
    }

    @Test
    void shouldRegisterMultipleModulesFromSameLargeRepository() throws Exception {
        Path workspace = initializeWorkspace();
        Path repository = createRepository();
        Path firstModule = createModule(repository, "algorithms/one", "<project/>");
        Path secondModule = createModule(repository, "algorithms/two", "<project/>");
        ProjectRegistry registry = registry();

        ProjectRegistrationResult first = registry.register(workspace, firstModule, Optional.empty());
        ProjectRegistrationResult second = registry.register(workspace, secondModule, Optional.empty());

        assertEquals(first.registration().repositoryRoot(), second.registration().repositoryRoot());
        assertNotEquals(first.registration().projectId(), second.registration().projectId());
        assertNotEquals(first.registration().moduleRoot(), second.registration().moduleRoot());
    }

    @Test
    void shouldRejectModuleWithoutRegularPom() throws Exception {
        Path workspace = initializeWorkspace();
        Path repository = createRepository();
        Path module = Files.createDirectories(repository.resolve("algorithm-without-pom"));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> registry().register(workspace, module, Optional.empty()));

        assertEquals("PROJECT_NOT_MAVEN", failure.code());
    }

    @Test
    void shouldRejectMalformedExistingProjectJsonBeforeCreatingAnotherProject() throws Exception {
        Path workspace = initializeWorkspace();
        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        Path invalidProject = layout.projectWorkspace(new ProjectId("invalid-project"));
        Files.createDirectories(invalidProject);
        Files.writeString(invalidProject.resolve("project.json"), "{", StandardCharsets.UTF_8);
        Path module = createModule(createRepository(), "new-project", "<project/>");

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> registry().register(workspace, module, Optional.empty()));

        assertEquals("PROJECT_REGISTRATION_INVALID", failure.code());
    }

    private Path initializeWorkspace() {
        Path workspace = temporaryDirectory.resolve("agent-workspace");
        new WorkspaceInitializer(
                manifestRepository(),
                new AtomicDocumentWriter(),
                new ClasspathWorkspaceTemplateProvider(),
                Clock.fixed(REGISTERED_AT, ZoneOffset.UTC))
                .initialize(workspace);
        return workspace;
    }

    private Path createRepository() throws IOException {
        Path repository = temporaryDirectory.resolve("large-system");
        Files.createDirectories(repository.resolve(".git"));
        return repository.toRealPath();
    }

    private static Path createModule(Path repository, String relativePath, String pom) throws IOException {
        Path module = Files.createDirectories(repository.resolve(relativePath));
        Files.writeString(module.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        return module.toRealPath();
    }

    private static ProjectRegistry registry() {
        return new ProjectRegistry(
                manifestRepository(),
                new ProjectRegistrationRepository(new BoundedDocumentMapper(), new AtomicDocumentWriter()),
                new RepositoryRootLocator(),
                new ProjectIdGenerator(),
                Clock.fixed(REGISTERED_AT, ZoneOffset.UTC));
    }

    private static WorkspaceManifestRepository manifestRepository() {
        return new WorkspaceManifestRepository(new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    private static String portable(Path path) throws IOException {
        return path.toRealPath().toString().replace('\\', '/');
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    snapshot.put(relative + "/", "directory");
                } else {
                    snapshot.put(relative, sha256(path));
                }
            }
        }
        return snapshot;
    }
}
