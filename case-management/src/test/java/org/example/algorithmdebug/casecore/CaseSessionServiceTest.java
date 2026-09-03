package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseSessionServiceTest {

    private static final Instant TIME = Instant.parse("2026-08-16T00:00:00Z");
    private static final ProjectId PROJECT = new ProjectId("project-1");
    private static final TargetTest TARGET = new TargetTest("a.b.ScheduleTest", "case1");

    @TempDir
    Path temporaryDirectory;

    private CaseArchiveRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        repository = new CaseArchiveRepository(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    @Test
    void shouldCreateInitialAnalysisForNewCaseWithoutContextDirectory() {
        CaseSessionService service = service("1", "1");

        CaseOpenResult result = service.open(request(Optional.empty()));

        assertTrue(result.caseCreated());
        assertEquals("case-1", result.caseId().value());
        assertEquals("analysis-1", result.analysisId().value());
        assertEquals("wafer-demo", repository.requireCase(result.caseId()).adapterId());
        assertEquals(1, result.digest().analysisCount());
        assertTrue(Files.notExists(temporaryDirectory.resolve("cases/case-1/contexts")));
    }

    @Test
    void shouldReuseCaseAndAppendAnalysis() {
        CaseSessionService service = service("1", "1", "2");
        CaseOpenResult first = service.open(request(Optional.empty()));

        CaseOpenResult second = service.open(new CaseSessionRequest(
                Optional.of(first.caseId()), PROJECT, TARGET, "wafer-demo", "继续调查"));

        assertEquals(first.caseId(), second.caseId());
        assertNotEquals(first.analysisId(), second.analysisId());
        assertEquals(2, second.digest().analysisCount());
        assertTrue(Files.notExists(temporaryDirectory.resolve("cases/case-1/contexts")));
    }

    @Test
    void shouldRejectDifferentTargetOrAdapterForExistingCase() {
        CaseSessionService service = service("1", "1");
        CaseOpenResult first = service.open(request(Optional.empty()));

        WorkspaceException targetFailure = assertThrows(WorkspaceException.class, () -> service.open(
                new CaseSessionRequest(Optional.of(first.caseId()), PROJECT,
                        new TargetTest("a.b.ScheduleTest", "case2"), "wafer-demo", "问题")));
        WorkspaceException adapterFailure = assertThrows(WorkspaceException.class, () -> service.open(
                new CaseSessionRequest(Optional.of(first.caseId()), PROJECT, TARGET,
                        "another-adapter", "问题")));

        assertEquals("CASE_TARGET_TEST_MISMATCH", targetFailure.code());
        assertEquals("CASE_ADAPTER_MISMATCH", adapterFailure.code());
    }

    private CaseSessionRequest request(Optional<CaseId> caseId) {
        return new CaseSessionRequest(
                caseId, PROJECT, TARGET, "wafer-demo", "问题一");
    }

    private CaseSessionService service(String... ids) {
        ArrayDeque<String> values = new ArrayDeque<>(List.of(ids));
        return new CaseSessionService(
                repository,
                new CaseDigestReader(repository),
                new OpaqueIdGenerator(values::removeFirst),
                Clock.fixed(TIME, ZoneOffset.UTC));
    }
}
