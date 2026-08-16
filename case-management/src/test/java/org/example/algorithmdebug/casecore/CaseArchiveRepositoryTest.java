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
