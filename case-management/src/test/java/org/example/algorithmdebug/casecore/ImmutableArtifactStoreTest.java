package org.example.algorithmdebug.casecore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImmutableArtifactStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldNotFallbackWhenAtomicMoveIsUnsupported() throws Exception {
        Path source = temporaryDirectory.resolve("source.json");
        Path runRoot = Files.createDirectory(temporaryDirectory.resolve("run"));
        Files.writeString(source, "{}");
        ImmutableArtifactStore store = new ImmutableArtifactStore((from, to) -> {
            throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "unsupported");
        });

        assertThrows(AtomicMoveNotSupportedException.class, () -> store.copy(
                source, runRoot, Path.of("raw/gantt.json"), "gantt", "GANTT", "application/json"));
        assertFalse(Files.exists(runRoot.resolve("raw/gantt.json")));
    }
}
