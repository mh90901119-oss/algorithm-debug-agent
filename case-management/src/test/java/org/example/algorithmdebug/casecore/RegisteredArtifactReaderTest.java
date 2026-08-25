package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.example.algorithmdebug.contracts.ArtifactTextExcerpt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredArtifactReaderTest {
    @TempDir Path temporaryDirectory;
    private Path casesRoot;
    private CaseArchiveRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        repository = new CaseArchiveRepository(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
        repository.createCase(CaseArchiveRepositoryTest.manifest());
    }

    @Test
    void readsOnlyRegisteredArtifactAndReturnsDeterministicContinuationOffset() throws Exception {
        Path file = repository.layout(CaseArchiveRepositoryTest.manifest().caseId())
                .caseRoot().resolve("sample.jsonl");
        Files.writeString(file, "第一行\nsecond\n");
        var reference = new CaseArtifactAccess(casesRoot).describe(
                CaseArchiveRepositoryTest.manifest().caseId(), "artifact-1", "TRACE",
                "application/x-ndjson", file);
        repository.registerArtifact(CaseArchiveRepositoryTest.manifest().caseId(), reference,
                Instant.parse("2026-08-19T00:00:00Z"));

        ArtifactTextExcerpt first = new RegisteredArtifactReader(repository).read(
                CaseArchiveRepositoryTest.manifest().caseId(), "artifact-1", 0, 10);
        ArtifactTextExcerpt second = new RegisteredArtifactReader(repository).read(
                CaseArchiveRepositoryTest.manifest().caseId(), "artifact-1",
                first.nextOffsetBytes(), 64);

        assertEquals("第一行\n", first.text());
        assertTrue(first.truncated());
        assertEquals("second\n", second.text());
        assertFalse(second.truncated());
    }

    @Test
    void rejectsUnknownTraversalAndChangedFile() throws Exception {
        RegisteredArtifactReader reader = new RegisteredArtifactReader(repository);
        assertEquals("CASE_ARTIFACT_NOT_REGISTERED", assertThrows(
                WorkspaceException.class, () -> reader.read(
                        CaseArchiveRepositoryTest.manifest().caseId(), "unknown", 0, 32)).code());
        assertThrows(IllegalArgumentException.class, () -> reader.read(
                CaseArchiveRepositoryTest.manifest().caseId(), "../outside", 0, 32));

        Path caseRoot = repository.layout(CaseArchiveRepositoryTest.manifest().caseId()).caseRoot();
        Path changed = Files.writeString(caseRoot.resolve("changed.txt"), "before");
        var changedReference = new CaseArtifactAccess(casesRoot).describe(
                CaseArchiveRepositoryTest.manifest().caseId(), "changed", "LOG", "text/plain",
                changed);
        repository.registerArtifact(CaseArchiveRepositoryTest.manifest().caseId(), changedReference,
                Instant.parse("2026-08-19T00:00:00Z"));
        Files.writeString(changed, "after");
        assertEquals("CASE_ARTIFACT_INTEGRITY_MISMATCH", assertThrows(
                WorkspaceException.class, () -> reader.read(
                        CaseArchiveRepositoryTest.manifest().caseId(), "changed", 0, 32)).code());

    }

    @Test
    void identicalRegistrationIsIdempotentButConflictingIdentityIsRejected() throws Exception {
        Path caseRoot = repository.layout(CaseArchiveRepositoryTest.manifest().caseId()).caseRoot();
        Path first = Files.writeString(caseRoot.resolve("plan.json"), "{\"plan\":1}");
        var reference = new CaseArtifactAccess(casesRoot).describe(
                CaseArchiveRepositoryTest.manifest().caseId(), "plan-1", "JDWP_PLAN",
                "application/json", first);

        Path created = repository.registerArtifact(
                CaseArchiveRepositoryTest.manifest().caseId(), reference,
                Instant.parse("2026-08-19T00:00:00Z"));
        Path reused = repository.registerArtifact(
                CaseArchiveRepositoryTest.manifest().caseId(), reference,
                Instant.parse("2026-08-19T00:01:00Z"));

        assertEquals(created, reused);
        assertEquals(Instant.parse("2026-08-19T00:00:00Z"),
                repository.requireArtifactRegistration(
                        CaseArchiveRepositoryTest.manifest().caseId(), "plan-1").registeredAt());

        Path second = Files.writeString(caseRoot.resolve("other-plan.json"), "{\"plan\":2}");
        var conflict = new CaseArtifactAccess(casesRoot).describe(
                CaseArchiveRepositoryTest.manifest().caseId(), "plan-1", "JDWP_PLAN",
                "application/json", second);
        assertEquals("CASE_ARTIFACT_INTEGRITY_MISMATCH", assertThrows(
                WorkspaceException.class, () -> repository.registerArtifact(
                        CaseArchiveRepositoryTest.manifest().caseId(), conflict,
                        Instant.parse("2026-08-19T00:02:00Z"))).code());
    }

    @Test
    void rejectsSymlinkEscapeWhenPlatformAllowsCreatingOne() throws Exception {
        Path caseRoot = repository.layout(CaseArchiveRepositoryTest.manifest().caseId()).caseRoot();
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "outside");
        Path link = caseRoot.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception unsupported) {
            assumeTrue(false, "当前 Windows 环境不允许创建测试符号链接");
        }
        assertEquals("CASE_ARTIFACT_PATH_INVALID", assertThrows(
                WorkspaceException.class, () -> new CaseArtifactAccess(casesRoot).describe(
                        CaseArchiveRepositoryTest.manifest().caseId(), "link", "LOG",
                        "text/plain", link)).code());
    }
}
