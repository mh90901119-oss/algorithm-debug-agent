package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactIntegrityCheckerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsTheRegisteredRegularFile() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("artifact.txt"), "content");
        ArtifactReference reference = reference(file);

        ArtifactIntegrityChecker.Result result =
                new ArtifactIntegrityChecker().verify(reference, file);

        assertEquals(ArtifactIntegrityChecker.Status.VALID, result.status());
    }

    @Test
    void distinguishesMissingNonRegularSizeAndHashFailures() throws Exception {
        Path missing = temporaryDirectory.resolve("missing.txt");
        ArtifactReference missingReference = new ArtifactReference(
                "missing", "TRACE", "missing.txt", "text/plain", "0".repeat(64), 1);
        assertEquals(ArtifactIntegrityChecker.Status.MISSING,
                new ArtifactIntegrityChecker().verify(missingReference, missing).status());

        Path directory = Files.createDirectory(temporaryDirectory.resolve("directory"));
        ArtifactReference directoryReference = new ArtifactReference(
                "directory", "TRACE", "directory", "text/plain", "0".repeat(64), 0);
        assertEquals(ArtifactIntegrityChecker.Status.NOT_REGULAR,
                new ArtifactIntegrityChecker().verify(directoryReference, directory).status());

        Path resized = Files.writeString(temporaryDirectory.resolve("resized.txt"), "before");
        ArtifactReference resizedReference = reference(resized);
        Files.writeString(resized, "longer-content");
        assertEquals(ArtifactIntegrityChecker.Status.SIZE_MISMATCH,
                new ArtifactIntegrityChecker().verify(resizedReference, resized).status());

        Path changed = Files.writeString(temporaryDirectory.resolve("changed.txt"), "before");
        ArtifactReference changedReference = reference(changed);
        Files.writeString(changed, "after!");
        assertEquals(ArtifactIntegrityChecker.Status.HASH_MISMATCH,
                new ArtifactIntegrityChecker().verify(changedReference, changed).status());
    }

    private ArtifactReference reference(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        return new ArtifactReference(
                path.getFileName().toString(), "TRACE", path.getFileName().toString(),
                "text/plain", sha256, bytes.length);
    }
}
