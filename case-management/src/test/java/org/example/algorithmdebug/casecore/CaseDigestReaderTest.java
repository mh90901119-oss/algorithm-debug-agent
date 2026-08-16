package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseDigestReaderTest {

    @TempDir
    Path temporaryDirectory;

    private CaseArchiveRepository repository;
    private CaseDigestReader reader;

    @BeforeEach
    void setUp() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        repository = new CaseArchiveRepository(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
        reader = new CaseDigestReader(repository);
        repository.createCase(CaseArchiveRepositoryTest.manifest());
        repository.createContext(CaseArchiveRepositoryTest.context());
        repository.createAnalysis(CaseArchiveRepositoryTest.analysis());
    }

    @Test
    void shouldReportRunWithoutOutcomeAsIncomplete() {
        RunRequest request = CaseArchiveRepositoryTest.run(
                new RunId("run-1"), Instant.parse("2026-08-16T00:00:03Z"));
        repository.startRun(request);

        CaseDigest digest = reader.read(request.caseId());

        assertEquals(List.of(new RunId("run-1")), digest.incompleteRuns());
        assertTrue(digest.latestRunId().isEmpty());
        assertEquals(1, digest.runCount());
    }

    @Test
    void shouldDeriveLatestRunWithoutRewritingEarlierOutcome() throws Exception {
        RunId firstId = new RunId("run-1");
        RunId secondId = new RunId("run-2");
        repository.startRun(CaseArchiveRepositoryTest.run(
                firstId, Instant.parse("2026-08-16T00:00:03Z")));
        repository.completeRun(outcome(firstId));
        Path firstOutcome = CaseArchiveLayout.of(
                temporaryDirectory.resolve("cases"), CaseArchiveRepositoryTest.manifest().caseId())
                .runOutcome(firstId);
        byte[] firstBytes = Files.readAllBytes(firstOutcome);

        repository.startRun(CaseArchiveRepositoryTest.run(
                secondId, Instant.parse("2026-08-16T00:00:04Z")));
        repository.completeRun(outcome(secondId));
        CaseDigest digest = reader.read(CaseArchiveRepositoryTest.manifest().caseId());

        assertArrayEquals(firstBytes, Files.readAllBytes(firstOutcome));
        assertEquals(secondId, digest.latestRunId().orElseThrow());
        assertEquals(List.of(secondId, firstId),
                digest.recentRuns().stream().map(RunOutcomeSummary::runId).toList());
    }

    @Test
    void shouldWarnAboutCorruptChildAndContinueReadingValidFacts() throws Exception {
        CaseId caseId = CaseArchiveRepositoryTest.manifest().caseId();
        CaseArchiveLayout layout = CaseArchiveLayout.of(temporaryDirectory.resolve("cases"), caseId);
        Path corrupt = layout.contextDocument(new org.example.algorithmdebug.contracts.ContextId("context-broken"));
        Files.createDirectories(corrupt.getParent());
        Files.writeString(corrupt, "{", StandardCharsets.UTF_8);

        CaseDigest digest = reader.read(caseId);

        assertEquals(1, digest.contextCount());
        assertFalse(digest.archiveWarnings().isEmpty());
        assertEquals("CASE_CHILD_DOCUMENT_INVALID", digest.archiveWarnings().getFirst().code());
        assertEquals(CaseArchiveRepositoryTest.context().contextId(),
                digest.latestContextId().orElseThrow());
    }

    @Test
    void shouldUseNewestAnalysisQuestionAndBoundExcerpt() {
        AnalysisRequest newest = new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST,
                CaseArchiveRepositoryTest.manifest().caseId(),
                CaseArchiveRepositoryTest.context().contextId(),
                new AnalysisId("analysis-2"), "问".repeat(3_000),
                Instant.parse("2026-08-16T00:00:05Z"));
        repository.createAnalysis(newest);

        CaseDigest digest = reader.read(newest.caseId());

        assertEquals(newest.analysisId(), digest.latestAnalysisId().orElseThrow());
        assertEquals(2_048, digest.latestQuestionExcerpt().length());
    }

    private static RunOutcomeSummary outcome(RunId runId) {
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED",
                CaseArchiveRepositoryTest.manifest().caseId(),
                CaseArchiveRepositoryTest.context().contextId(),
                CaseArchiveRepositoryTest.analysis().analysisId(), runId,
                ProcessOutcome.SUCCEEDED, TestOutcome.PASSED, GanttOutcome.ABSENT,
                Optional.empty(), Optional.empty(), ComparisonOutcome.NOT_COMPARED,
                "Baseline comparison is not implemented in this slice", List.of());
    }
}
