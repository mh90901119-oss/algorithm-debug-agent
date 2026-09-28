package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
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

    @Test
    void shouldPublishAnalysisManifestAndProblemFrameAsOneInitializedAnalysis() {
        CaseSessionService service = service("unused");
        ProblemFrame frame = problemFrame("case-atomic", "analysis-atomic");

        CaseOpenResult result = service.open(CaseSessionRequest.initialized(
                Optional.empty(), PROJECT, "wafer-demo", frame));

        assertEquals(frame.caseId(), result.caseId());
        assertEquals(frame.analysisId(), result.analysisId());
        assertEquals(frame.analysisId(), repository.requireAnalysis(
                frame.caseId(), frame.analysisId()).analysisId());
        InvestigationJournalReader.Result journal = new InvestigationJournalReader(
                temporaryDirectory.resolve("cases"), new BoundedDocumentMapper())
                .readValidatedEvents(new AnalysisIdentity(PROJECT, frame.caseId(), frame.analysisId()));
        assertTrue(journal.limitations().isEmpty());
        assertEquals(1, journal.events().size());
        var initial = (org.example.algorithmdebug.contracts.investigation.InvestigationEvent
                .ProblemFrameDefined) journal.events().getFirst();
        assertEquals(frame, initial.problemFrame());
    }

    @Test
    void shouldRejectMismatchedExistingCaseBeforePublishingAnalysis() {
        CaseSessionService service = service("1", "1");
        CaseOpenResult first = service.open(request(Optional.empty()));
        ProblemFrame mismatched = problemFrame("another-case", "analysis-atomic");

        assertThrows(IllegalArgumentException.class, () -> service.open(
                CaseSessionRequest.initialized(
                        Optional.of(first.caseId()), PROJECT, "wafer-demo", mismatched)));

        assertTrue(Files.notExists(temporaryDirectory.resolve(
                "cases/case-1/analyses/analysis-atomic")));
    }

    @Test
    void shouldNotPublishHalfInitializedAnalysisWhenInitialEventWriteFails() throws Exception {
        Path casesRoot = temporaryDirectory.resolve("failing-cases");
        Files.createDirectories(casesRoot);
        AtomicDocumentWriter failingWriter = new AtomicDocumentWriter((source, target) -> {
            if (target.toString().replace('\\', '/').contains("/investigation/events/")) {
                throw new java.io.IOException("simulated event commit failure");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        });
        CaseArchiveRepository failingRepository = new CaseArchiveRepository(
                casesRoot, new BoundedDocumentMapper(), failingWriter);
        CaseSessionService service = new CaseSessionService(
                failingRepository, new CaseDigestReader(failingRepository),
                new OpaqueIdGenerator(() -> "unused"), Clock.fixed(TIME, ZoneOffset.UTC));
        ProblemFrame frame = problemFrame("case-failing", "analysis-failing");

        assertThrows(WorkspaceException.class, () -> service.open(
                CaseSessionRequest.initialized(
                        Optional.empty(), PROJECT, "wafer-demo", frame)));

        assertTrue(Files.notExists(casesRoot.resolve(
                "case-failing/analyses/analysis-failing")));
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

    private static ProblemFrame problemFrame(String caseId, String analysisId) {
        return new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME,
                new ProblemFrameId("problem-1"),
                new CaseId(caseId),
                new org.example.algorithmdebug.contracts.AnalysisId(analysisId),
                "调度结果顺序异常",
                "目标测试断言通过",
                "目标测试观察到错误顺序",
                TARGET,
                List.of(new SourceAnchor(
                        "a.b.Scheduler", "schedule", "()V",
                        "src/main/java/a/b/Scheduler.java", 10, 20)),
                List.of("target-test:a.b.ScheduleTest#case1"),
                List.of("候选选择分支是否错误"),
                TIME);
    }
}
