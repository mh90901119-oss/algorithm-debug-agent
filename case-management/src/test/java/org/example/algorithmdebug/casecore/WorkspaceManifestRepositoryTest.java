package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.WorkspaceManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManifestRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCreateAndRoundTripValidManifest() throws Exception {
        WorkspaceLayout layout = initializedLayout();
        WorkspaceManifestRepository repository = repository();
        WorkspaceManifest manifest = validManifest();

        repository.create(layout, manifest);

        assertEquals(manifest, repository.require(layout));
        assertEquals(manifest, repository.find(layout).orElseThrow());
        assertTrue(Files.isRegularFile(layout.root().resolve("workspace.yaml")));
    }

    @Test
    void shouldReturnEmptyOnlyWhenManifestDoesNotExist() throws Exception {
        WorkspaceLayout layout = initializedLayout();

        assertFalse(repository().find(layout).isPresent());
        assertThrows(WorkspaceException.class, () -> repository().require(layout));
    }

    @Test
    void shouldRejectUnsupportedWorkspaceSchemaWithOriginalCause() throws Exception {
        WorkspaceLayout layout = initializedLayout();
        Files.writeString(
                layout.root().resolve("workspace.yaml"),
                "schemaVersion: \"9.0\"\nkind: ALGORITHM_DEBUG_WORKSPACE\ncreatedAt: 2026-08-16T00:00:00Z\n",
                StandardCharsets.UTF_8);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> repository().require(layout));

        assertNotNull(failure.getCause());
        assertTrue(rootMessage(failure).contains("9.0"));
    }

    @Test
    void shouldRejectMalformedYamlWithOriginalCause() throws Exception {
        WorkspaceLayout layout = initializedLayout();
        Files.writeString(
                layout.root().resolve("workspace.yaml"),
                "schemaVersion: [\n",
                StandardCharsets.UTF_8);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> repository().require(layout));

        assertNotNull(failure.getCause());
    }

    @Test
    void shouldRejectDocumentLargerThanOneMebibyteBeforeParsing() throws Exception {
        WorkspaceLayout layout = initializedLayout();
        byte[] oversized = new byte[BoundedDocumentMapper.MAX_DOCUMENT_BYTES + 1];
        Files.write(layout.root().resolve("workspace.yaml"), oversized);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> repository().require(layout));

        assertTrue(failure.getMessage().contains("1048576"));
    }

    @Test
    void shouldBoundSerializedDocumentsAndPreserveSerializationCause() {
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        OversizedDocument oversized = new OversizedDocument("x".repeat(BoundedDocumentMapper.MAX_DOCUMENT_BYTES));
        SelfReferentialDocument recursive = new SelfReferentialDocument();
        recursive.self = recursive;

        assertThrows(WorkspaceException.class, () -> mapper.writeJson(oversized));
        WorkspaceException failure = assertThrows(WorkspaceException.class, () -> mapper.writeYaml(recursive));
        assertNotNull(failure.getCause());
    }

    private WorkspaceLayout initializedLayout() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.of(temporaryDirectory.resolve("workspace"));
        Files.createDirectories(layout.root());
        return layout;
    }

    private static WorkspaceManifestRepository repository() {
        return new WorkspaceManifestRepository(new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    private static WorkspaceManifest validManifest() {
        return new WorkspaceManifest(
                SchemaVersions.WORKSPACE_MANIFEST,
                WorkspaceManifest.KIND,
                Instant.parse("2026-08-16T00:00:00Z"));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    private record OversizedDocument(String value) {
    }

    private static final class SelfReferentialDocument {
        private SelfReferentialDocument self;

        public SelfReferentialDocument getSelf() {
            return self;
        }
    }
}
