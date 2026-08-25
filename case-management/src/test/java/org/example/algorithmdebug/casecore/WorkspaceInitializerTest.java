package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.WorkspaceInitializationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceInitializerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsOnlyWorkspaceManifestAndIsIdempotent() throws Exception {
        Path root = temporaryDirectory.resolve("workspace");
        WorkspaceInitializer initializer = initializer();

        WorkspaceInitializationResult first = initializer.initialize(root);
        WorkspaceInitializationResult second = initializer.initialize(root);

        assertTrue(first.created());
        assertFalse(second.created());
        try (var entries = Files.list(root)) {
            assertEquals(List.of("workspace.yaml"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static WorkspaceInitializer initializer() {
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        return new WorkspaceInitializer(
                new WorkspaceManifestRepository(new BoundedDocumentMapper(), writer),
                Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));
    }
}
