package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.BuildSnapshot;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.InputSnapshot;
import org.example.algorithmdebug.contracts.InputSnapshotStatus;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceSnapshot;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseArchiveRepositoryTest {

    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final TargetTest TARGET = new TargetTest("a.b.ScheduleTest", "case1");
    private static final Instant TIME = Instant.parse("2026-08-16T00:00:00Z");

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
    void shouldCreateAndReadAppendOnlyCaseDocuments() {
        CaseManifest manifest = manifest();
        ContextSnapshot context = context();
        AnalysisRequest analysis = analysis();
        RunRequest run = run(new RunId("run-1"), TIME.plusSeconds(3));

        repository.createCase(manifest);
        repository.createContext(context);
        repository.createAnalysis(analysis);
        repository.startRun(run);

        assertEquals(manifest, repository.requireCase(CASE_ID));
        assertEquals(context, repository.requireContext(CASE_ID, CONTEXT_ID));
        assertEquals(analysis, repository.requireAnalysis(CASE_ID, ANALYSIS_ID));
        assertEquals(run, repository.requireRunRequest(CASE_ID, run.runId()));
        assertTrue(Files.isDirectory(repository.runRawDirectory(CASE_ID, run.runId())));
    }

    @Test
    void shouldRejectEveryTerminalDocumentOverwrite() {
        repository.createCase(manifest());

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> repository.createCase(manifest()));

        assertEquals("CASE_ARCHIVE_WRITE_FAILED", failure.code());
        assertEquals("为什么有空闲？", repository.requireCase(CASE_ID).initialQuestion());
    }

    @Test
    void shouldRejectDocumentWhoseIdentityDoesNotBelongToCase() {
        repository.createCase(manifest());
        ContextSnapshot wrong = new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, CONTEXT_ID,
                new ProjectId("project-2"), TARGET, "UNAVAILABLE", source(), input(), build(),
                SnapshotCompleteness.COMPLETE, "d".repeat(64), List.of(), TIME);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> repository.createContext(wrong));

        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", failure.code());
    }

    @Test
    void shouldRejectRunWhoseAnalysisBelongsToAnotherContext() {
        repository.createCase(manifest());
        repository.createContext(context());
        repository.createAnalysis(analysis());
        ContextId secondContextId = new ContextId("context-2");
        repository.createContext(new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, secondContextId,
                PROJECT_ID, TARGET, "UNAVAILABLE", source(), input(), build(),
                SnapshotCompleteness.COMPLETE, "e".repeat(64), List.of(), TIME.plusSeconds(3)));
        RunRequest mismatched = new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, secondContextId, ANALYSIS_ID,
                new RunId("run-1"), TARGET, "UNINSTRUMENTED", TIME.plusSeconds(4));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> repository.startRun(mismatched));

        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", failure.code());
    }

    @Test
    void shouldCreateRunFingerprintOnceAndValidateRunIdentity() {
        prepareRun(run(new RunId("run-1"), TIME.plusSeconds(3)));
        RunResultFingerprint fingerprint = ganttFingerprint(
                CONTEXT_ID, new RunId("run-1"), "a", "b");

        Path created = repository.createRunResultFingerprint(fingerprint);

        assertEquals(
                temporaryDirectory.resolve(
                        "cases/case-1/runs/run-1/run-result-fingerprint.json"),
                created);
        WorkspaceException overwrite = assertThrows(
                WorkspaceException.class,
                () -> repository.createRunResultFingerprint(fingerprint));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", overwrite.code());

        ContextId wrongContext = new ContextId("context-2");
        WorkspaceException mismatch = assertThrows(
                WorkspaceException.class,
                () -> repository.createRunResultFingerprint(ganttFingerprint(
                        wrongContext, new RunId("run-1"), "a", "b")));
        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", mismatch.code());
    }

    @Test
    void shouldKeepFirstContextReproductionReference() {
        prepareRun(run(new RunId("run-1"), TIME.plusSeconds(3)));
        RunResultFingerprint first = ganttFingerprint(
                CONTEXT_ID, new RunId("run-1"), "a", "b");
        repository.createRunResultFingerprint(first);
        repository.startRun(run(new RunId("run-2"), TIME.plusSeconds(4)));
        RunResultFingerprint second = ganttFingerprint(
                CONTEXT_ID, new RunId("run-2"), "c", "d");
        repository.createRunResultFingerprint(second);

        assertEquals(first, repository.createReproductionIfAbsent(first));
        assertEquals(first, repository.createReproductionIfAbsent(second));
        assertEquals(Optional.of(first), repository.findReproduction(CASE_ID, CONTEXT_ID));
    }

    @Test
    void shouldSelectLatestOlderContextByTimestampThenContextId() {
        repository.createCase(manifest());
        ContextId firstId = new ContextId("context-a");
        ContextId secondId = new ContextId("context-b");
        ContextId currentId = new ContextId("context-c");
        createContextRunAndReproduction(firstId, "analysis-a", "run-a", TIME.plusSeconds(1), "a");
        createContextRunAndReproduction(secondId, "analysis-b", "run-b", TIME.plusSeconds(1), "b");
        repository.createContext(context(currentId, TIME.plusSeconds(2), "f"));

        Optional<RunResultFingerprint> selected =
                repository.findLatestReproductionBefore(CASE_ID, currentId);

        assertEquals(Optional.of(new RunId("run-b")), selected.map(RunResultFingerprint::runId));
    }

    @Test
    void shouldRejectReproductionWhoseIdentityDoesNotMatchItsPath() throws Exception {
        repository.createCase(manifest());
        repository.createContext(context());
        Path reproduction = temporaryDirectory.resolve(
                "cases/case-1/contexts/context-1/reproduction.json");
        RunResultFingerprint wrong = ganttFingerprint(
                new ContextId("context-2"), new RunId("run-1"), "a", "b");
        Files.write(reproduction, new BoundedDocumentMapper().writeJson(wrong));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> repository.findReproduction(CASE_ID, CONTEXT_ID));

        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", failure.code());
    }

    @Test
    void shouldNotSkipCorruptedLatestOlderReproduction() throws Exception {
        repository.createCase(manifest());
        ContextId olderId = new ContextId("context-a");
        ContextId latestOlderId = new ContextId("context-b");
        ContextId currentId = new ContextId("context-c");
        createContextRunAndReproduction(olderId, "analysis-a", "run-a", TIME.plusSeconds(1), "a");
        createContextRunAndReproduction(
                latestOlderId, "analysis-b", "run-b", TIME.plusSeconds(2), "b");
        repository.createContext(context(currentId, TIME.plusSeconds(3), "f"));
        Files.writeString(temporaryDirectory.resolve(
                "cases/case-1/contexts/context-b/reproduction.json"), "{broken");

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> repository.findLatestReproductionBefore(CASE_ID, currentId));

        assertEquals("CASE_DOCUMENT_INVALID", failure.code());
    }

    @Test
    void layoutRejectsOpaqueIdsThatWouldEscapeArchiveRoots() {
        Path casesRoot = temporaryDirectory.resolve("cases");

        assertThrows(IllegalArgumentException.class,
                () -> CaseArchiveLayout.of(casesRoot, new CaseId("../outside")));
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, CASE_ID);
        assertThrows(IllegalArgumentException.class,
                () -> layout.contextRoot(new ContextId("../outside")));
        assertThrows(IllegalArgumentException.class,
                () -> layout.runResultFingerprint(new RunId("../outside")));
    }

    static CaseManifest manifest() {
        return new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET,
                "为什么有空闲？", TIME);
    }

    static ContextSnapshot context() {
        return new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, CONTEXT_ID, PROJECT_ID, TARGET,
                "UNAVAILABLE", source(), input(), build(), SnapshotCompleteness.COMPLETE,
                "d".repeat(64), List.of(), TIME.plusSeconds(1));
    }

    static AnalysisRequest analysis() {
        return new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                "继续分析空闲", TIME.plusSeconds(2));
    }

    static RunRequest run(RunId runId, Instant createdAt) {
        return new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                runId, TARGET, "UNINSTRUMENTED", createdAt);
    }

    private void prepareRun(RunRequest request) {
        repository.createCase(manifest());
        repository.createContext(context());
        repository.createAnalysis(analysis());
        repository.startRun(request);
    }

    private void createContextRunAndReproduction(
            ContextId contextId,
            String analysisId,
            String runId,
            Instant createdAt,
            String hashSeed) {
        repository.createContext(context(contextId, createdAt, hashSeed));
        AnalysisId analysisIdValue = new AnalysisId(analysisId);
        repository.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, contextId, analysisIdValue,
                "继续分析", createdAt.plusMillis(1)));
        RunId runIdValue = new RunId(runId);
        repository.startRun(new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, contextId, analysisIdValue,
                runIdValue, TARGET, "UNINSTRUMENTED", createdAt.plusMillis(2)));
        RunResultFingerprint fingerprint = ganttFingerprint(
                contextId, runIdValue, hashSeed, hashSeed);
        repository.createRunResultFingerprint(fingerprint);
        repository.createReproductionIfAbsent(fingerprint);
    }

    private static ContextSnapshot context(
            ContextId contextId, Instant createdAt, String hashSeed) {
        return new ContextSnapshot(
                SchemaVersions.CONTEXT_SNAPSHOT, CASE_ID, contextId, PROJECT_ID, TARGET,
                "UNAVAILABLE", source(), input(), build(), SnapshotCompleteness.COMPLETE,
                hashSeed.repeat(64), List.of(), createdAt);
    }

    private static RunResultFingerprint ganttFingerprint(
            ContextId contextId,
            RunId runId,
            String rawSeed,
            String normalizedSeed) {
        return new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, contextId, runId,
                Optional.of(rawSeed.repeat(64)), Optional.of(normalizedSeed.repeat(64)),
                Optional.empty());
    }

    private static SourceSnapshot source() {
        return new SourceSnapshot("a".repeat(64), 1, 10, SnapshotCompleteness.COMPLETE);
    }

    private static InputSnapshot input() {
        return new InputSnapshot(InputSnapshotStatus.PRESENT, "input/case.json", "b".repeat(64), 10, "");
    }

    private static BuildSnapshot build() {
        return new BuildSnapshot("c".repeat(64), "21", "wafer-demo", "0.2.0");
    }
}
