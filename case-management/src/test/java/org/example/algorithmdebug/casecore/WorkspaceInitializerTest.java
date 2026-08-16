package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.WorkspaceInitializationResult;
import org.example.algorithmdebug.contracts.WorkspaceManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceInitializerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCreateStandardWorkspaceWithoutInstallationMarker() throws Exception {
        Path requestedRoot = temporaryDirectory.resolve("parent/../workspace");
        WorkspaceInitializer initializer = initializerWithFixedClock();

        WorkspaceInitializationResult result = initializer.initialize(requestedRoot);

        WorkspaceLayout layout = WorkspaceLayout.of(requestedRoot);
        assertTrue(result.created());
        assertEquals(layout.root().toString(), result.workspaceRoot());
        assertEquals(SchemaVersions.WORKSPACE_MANIFEST, result.schemaVersion());
        assertStandardDirectories(layout);
        assertTrue(Files.isRegularFile(layout.root().resolve("workspace.yaml")));
        assertEquals(
                new WorkspaceManifest(SchemaVersions.WORKSPACE_MANIFEST, WorkspaceManifest.KIND, CREATED_AT),
                repository().require(layout));
        assertEquals(
                Set.of("application.yaml", "execution.yaml", "collection-limits.yaml", "security-policy.yaml"),
                configurationFileNames(layout));
        assertTrue(Files.notExists(layout.systemRoot().resolve("installation.json")));
    }

    @Test
    void shouldBeIdempotentAndPreserveUserConfiguration() throws Exception {
        WorkspaceInitializer initializer = initializerWithFixedClock();
        Path root = temporaryDirectory.resolve("workspace");
        WorkspaceInitializationResult first = initializer.initialize(root);
        Path application = root.resolve("config/application.yaml");
        String userConfiguration = "schemaVersion: \"1.0\"\noffline: false\n";
        Files.writeString(application, userConfiguration, StandardCharsets.UTF_8);

        WorkspaceInitializationResult second = initializer.initialize(root);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(userConfiguration, Files.readString(application, StandardCharsets.UTF_8));
        assertEquals(CREATED_AT, repository().require(WorkspaceLayout.of(root)).createdAt());
    }

    @Test
    void shouldRejectUnsupportedExistingManifestWithoutCreatingTemplates() throws Exception {
        Path root = temporaryDirectory.resolve("workspace");
        Files.createDirectories(root);
        Files.writeString(
                root.resolve("workspace.yaml"),
                "schemaVersion: \"9.0\"\nkind: ALGORITHM_DEBUG_WORKSPACE\ncreatedAt: 2026-08-16T00:00:00Z\n",
                StandardCharsets.UTF_8);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> initializerWithFixedClock().initialize(root));

        assertTrue(rootMessage(failure).contains("9.0"));
        assertTrue(Files.notExists(root.resolve("config")));
    }

    @Test
    void shouldLoadExactlyFourBoundedClasspathTemplates() {
        WorkspaceTemplateProvider provider = new ClasspathWorkspaceTemplateProvider();

        var templates = provider.templates();

        assertEquals(
                Set.of(
                        Path.of("application.yaml"),
                        Path.of("execution.yaml"),
                        Path.of("collection-limits.yaml"),
                        Path.of("security-policy.yaml")),
                templates.keySet());
        assertTrue(templates.values().stream()
                .allMatch(content -> content.length > 0 && content.length <= BoundedDocumentMapper.MAX_DOCUMENT_BYTES));
        String application = new String(templates.get(Path.of("application.yaml")), StandardCharsets.UTF_8);
        String limits = new String(templates.get(Path.of("collection-limits.yaml")), StandardCharsets.UTF_8);
        assertEquals("schemaVersion: \"1.0\"\noffline: true\n", application);
        assertFalse(application.contains("caseRoot"));
        assertTrue(limits.contains("maxTotalRuns: 8\n"));
        assertTrue(limits.contains("maxCodePathRuns: 3\n"));
        assertTrue(limits.contains("maxJdwpRuns: 4\n"));
        assertThrows(UnsupportedOperationException.class,
                () -> templates.put(Path.of("extra.yaml"), new byte[0]));
    }

    @Test
    void shouldRejectEscapingTemplatePathWithoutWritingOutsideWorkspace() {
        Path root = temporaryDirectory.resolve("workspace");
        WorkspaceTemplateProvider escapingProvider = () -> Map.of(
                Path.of("..", "outside.yaml"), "unsafe".getBytes(StandardCharsets.UTF_8));
        WorkspaceInitializer initializer = new WorkspaceInitializer(
                repository(),
                new AtomicDocumentWriter(),
                escapingProvider,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));

        assertThrows(WorkspaceException.class, () -> initializer.initialize(root));
        assertTrue(Files.notExists(root.resolve("outside.yaml")));
        assertTrue(Files.notExists(temporaryDirectory.resolve("outside.yaml")));
    }

    private WorkspaceInitializer initializerWithFixedClock() {
        return new WorkspaceInitializer(
                repository(),
                new AtomicDocumentWriter(),
                new ClasspathWorkspaceTemplateProvider(),
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));
    }

    private static WorkspaceManifestRepository repository() {
        return new WorkspaceManifestRepository(new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    private static void assertStandardDirectories(WorkspaceLayout layout) {
        Set<Path> expected = Set.of(
                layout.configRoot(),
                layout.configRoot().resolve("projects"),
                layout.root().resolve("knowledge/shared"),
                layout.projectsRoot(),
                layout.systemRoot(),
                layout.systemRoot().resolve("locks"),
                layout.systemRoot().resolve("indexes"),
                layout.systemRoot().resolve("logs"),
                layout.root().resolve("cache"),
                layout.root().resolve("temp"));
        assertTrue(expected.stream().allMatch(Files::isDirectory));
    }

    private static Set<String> configurationFileNames(WorkspaceLayout layout) throws Exception {
        try (var files = Files.list(layout.configRoot())) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }
}
