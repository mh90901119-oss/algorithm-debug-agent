package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.WorkspaceInitializer;
import org.example.algorithmdebug.casecore.WorkspaceManifestRepository;
import org.example.algorithmdebug.contracts.WorkspaceInitializationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceApplicationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReturnExactDomainResultWithoutPrinting() {
        WorkspaceInitializer initializer = initializer();
        WorkspaceApplicationService service = new WorkspaceApplicationService(initializer);
        Path workspace = temporaryDirectory.resolve("workspace");

        Captured<WorkspaceInitializationResult> captured = captureStdout(() -> service.initialize(workspace));

        assertTrue(captured.value().created());
        assertEquals(workspace.toAbsolutePath().normalize().toString(), captured.value().workspaceRoot());
        assertEquals("", captured.stdout());
    }

    private static WorkspaceInitializer initializer() {
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        return new WorkspaceInitializer(
                new WorkspaceManifestRepository(new BoundedDocumentMapper(), writer),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
    }

    private static <T> Captured<T> captureStdout(ThrowingSupplier<T> action) {
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
        T get();
    }
}
