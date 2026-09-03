package org.example.algorithmdebug.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.TraceProvenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvenanceVerifierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesExactRawLineAndToolSequence() throws Exception {
        Path raw = Files.writeString(temporaryDirectory.resolve("raw.jsonl"), """
                {"sequence":1,"eventType":"collector_started"}
                {"sequence":2,"eventType":"tracepoint_hit"}
                """);
        ArtifactReference reference = reference(raw);

        assertTrue(new ProvenanceVerifier().verify(
                List.of(provenance(reference, 2, Optional.empty(), Optional.of(2L))),
                reference, raw).isEmpty());
    }

    @Test
    void rejectsWrongArtifactLineAndEventIdentity() throws Exception {
        Path raw = Files.writeString(temporaryDirectory.resolve("raw.jsonl"), """
                {"eventId":1,"eventType":"METHOD_ENTER"}
                """);
        ArtifactReference reference = reference(raw);
        ArtifactReference other = new ArtifactReference(
                "other", "RAW", "raw/other.jsonl", "application/x-ndjson",
                reference.sha256(), reference.sizeBytes());

        var findings = new ProvenanceVerifier().verify(List.of(
                provenance(other, 1, Optional.of(1L), Optional.empty()),
                provenance(reference, 2, Optional.of(1L), Optional.empty()),
                provenance(reference, 1, Optional.of(2L), Optional.empty())), reference, raw);

        assertEquals(List.of(
                "PROVENANCE_ARTIFACT_MISMATCH",
                "PROVENANCE_LINE_OUT_OF_RANGE",
                "PROVENANCE_EVENT_ID_MISMATCH"),
                findings.stream().map(finding -> finding.code()).toList());
    }

    private static TraceProvenance provenance(
            ArtifactReference reference,
            long line,
            Optional<Long> eventId,
            Optional<Long> sequence) {
        return new TraceProvenance(
                new CaseId("case-1"), new RunId("run-1"),
                new CollectionId("collection-1"), reference, line, eventId, sequence,
                "RAW_OBSERVATION");
    }

    private static ArtifactReference reference(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new ArtifactReference(
                "raw-1", "RAW", "raw/raw.jsonl", "application/x-ndjson",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                bytes.length);
    }
}
