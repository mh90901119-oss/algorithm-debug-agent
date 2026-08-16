package org.example.algorithmdebug.casecore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicDocumentWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCreateNewDocumentWithoutLeavingTemporaryFile() throws Exception {
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        Path target = temporaryDirectory.resolve("workspace.yaml");
        byte[] content = "schemaVersion: \"1.0\"\n".getBytes(StandardCharsets.UTF_8);

        writer.writeNew(target, content);

        assertArrayEquals(content, Files.readAllBytes(target));
        assertEquals(1L, countDirectoryEntries());
    }

    @Test
    void shouldRejectOverwriteAndPreserveExistingDocument() throws Exception {
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        Path target = temporaryDirectory.resolve("workspace.yaml");
        Files.writeString(target, "original", StandardCharsets.UTF_8);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> writer.writeNew(target, "replacement".getBytes(StandardCharsets.UTF_8)));

        assertInstanceOf(java.nio.file.FileAlreadyExistsException.class, failure.getCause());
        assertEquals("original", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(1L, countDirectoryEntries());
    }

    @Test
    void shouldCleanTemporaryFileAndPreserveCauseWhenAtomicMoveFails() throws Exception {
        IOException moveFailure = new IOException("simulated atomic move failure");
        AtomicDocumentWriter writer = new AtomicDocumentWriter((source, target) -> {
            throw moveFailure;
        });
        Path target = temporaryDirectory.resolve("workspace.yaml");

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> writer.writeNew(target, "content".getBytes(StandardCharsets.UTF_8)));

        assertEquals(moveFailure, failure.getCause());
        assertTrue(Files.notExists(target));
        assertEquals(0L, countDirectoryEntries());
    }

    private long countDirectoryEntries() throws IOException {
        try (var entries = Files.list(temporaryDirectory)) {
            return entries.count();
        }
    }
}
