package org.example.algorithmdebug.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactIntegrityVerifierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsMatchingRegularArtifactAndRejectsTamper() throws Exception {
        Path artifact = Files.writeString(temporaryDirectory.resolve("raw.jsonl"), "{}\n");
        ArtifactReference reference = reference(artifact);
        ArtifactIntegrityVerifier verifier = new ArtifactIntegrityVerifier();

        assertTrue(verifier.verify(reference, artifact).isEmpty());

        Files.writeString(artifact, "{\"changed\":true}\n");
        var findings = verifier.verify(reference, artifact);
        assertEquals("ARTIFACT_SIZE_MISMATCH", findings.getFirst().code());
        assertEquals(EvidenceValidationStatus.INVALID, findings.getFirst().status());
    }

    @Test
    void rejectsMissingAndSymbolicLinkArtifacts() throws Exception {
        ArtifactIntegrityVerifier verifier = new ArtifactIntegrityVerifier();
        Path missing = temporaryDirectory.resolve("missing.jsonl");
        ArtifactReference missingReference = new ArtifactReference(
                "raw-1", "RAW", "raw/missing.jsonl", "application/x-ndjson",
                "a".repeat(64), 1);
        assertEquals("ARTIFACT_MISSING",
                verifier.verify(missingReference, missing).getFirst().code());

        Path real = Files.writeString(temporaryDirectory.resolve("real.jsonl"), "{}\n");
        Path link = temporaryDirectory.resolve("link.jsonl");
        try {
            Files.createSymbolicLink(link, real);
            assertEquals("ARTIFACT_NOT_REGULAR",
                    verifier.verify(reference(real), link).getFirst().code());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // 当前文件系统不支持符号链接时，缺失文件分支已覆盖路径安全行为。
        }
    }

    private static ArtifactReference reference(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new ArtifactReference(
                "raw-1", "RAW", "raw/raw.jsonl", "application/x-ndjson",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                bytes.length);
    }
}
