package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.ClasspathWorkspaceTemplateProvider;
import org.example.algorithmdebug.casecore.ProjectIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.ProjectRegistry;
import org.example.algorithmdebug.casecore.RepositoryRootLocator;
import org.example.algorithmdebug.casecore.WorkspaceInitializer;
import org.example.algorithmdebug.casecore.WorkspaceManifestRepository;
import org.example.algorithmdebug.contracts.ProjectRegistrationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectApplicationServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReturnExactDomainResultWithoutPrinting() throws Exception {
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        WorkspaceManifestRepository manifestRepository = new WorkspaceManifestRepository(
                new BoundedDocumentMapper(), writer);
        Path workspace = temporaryDirectory.resolve("workspace");
        new WorkspaceInitializer(
                manifestRepository, writer, new ClasspathWorkspaceTemplateProvider(), FIXED_CLOCK)
                .initialize(workspace);
        Path repository = Files.createDirectories(temporaryDirectory.resolve("large-system"));
        Files.createDirectories(repository.resolve(".git"));
        Path module = Files.createDirectories(repository.resolve("algorithm-module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        ProjectRegistry registry = new ProjectRegistry(
                manifestRepository,
                new ProjectRegistrationRepository(new BoundedDocumentMapper(), writer),
                new RepositoryRootLocator(),
                new ProjectIdGenerator(),
                FIXED_CLOCK);
        ProjectApplicationService service = new ProjectApplicationService(registry);

        Captured<ProjectRegistrationResult> captured = captureStdout(
                () -> service.register(workspace, module, Optional.empty()));

        assertTrue(captured.value().created());
        assertEquals(module.toRealPath().toString().replace('\\', '/'),
                captured.value().registration().moduleRoot());
        assertEquals("", captured.stdout());
    }

    private static <T> Captured<T> captureStdout(ThrowingSupplier<T> action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            return new Captured<>(action.get(), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private record Captured<T>(T value, String stdout) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
