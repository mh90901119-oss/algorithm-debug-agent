package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.CollectionBudget;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.MethodCatalogEntry;
import org.example.algorithmdebug.contracts.MethodCallEdge;
import org.example.algorithmdebug.contracts.MethodSelector;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseArchiveRepositoryTest {

    private static final CaseId CASE_ID = new CaseId("case-1");
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
        AnalysisRequest analysis = analysis();
        RunRequest run = run(new RunId("run-1"), TIME.plusSeconds(3));

        repository.createCase(manifest);
        repository.createAnalysis(analysis);
        repository.startRun(run);

        assertEquals(manifest, repository.requireCase(CASE_ID));
        assertEquals(analysis, repository.requireAnalysis(CASE_ID, ANALYSIS_ID));
        assertEquals(run, repository.requireRunRequest(CASE_ID, run.runId()));
        assertTrue(Files.isDirectory(repository.runRawDirectory(CASE_ID, run.runId())));
    }

    @Test
    void shouldArchiveMethodCatalogAndPlanOnceWithMatchingIdentity() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        MethodCatalog catalog = methodCatalog();
        CodePathCollectionPlan plan = codePathPlan();

        Path catalogPath = repository.createMethodCatalog(catalog);
        Path planPath = repository.createCodePathPlan(plan);

        assertEquals(catalog, repository.requireMethodCatalog(CASE_ID, ANALYSIS_ID));
        assertEquals(plan, repository.requireCodePathPlan(
                CASE_ID, ANALYSIS_ID, new PlanId("plan-1")));
        assertTrue(catalogPath.endsWith("analyses/analysis-1/method-catalog.json"));
        assertTrue(planPath.endsWith("analyses/analysis-1/plans/plan-1.json"));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class, () -> repository.createMethodCatalog(catalog)).code());
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class, () -> repository.createCodePathPlan(plan)).code());
    }

    @Test
    void shouldStreamMethodCatalogLargerThanControlDocumentLimitAtomically() throws Exception {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        MethodCatalogEntry target = methodCatalog().entries().getFirst();
        List<MethodCallEdge> edges = IntStream.range(0, 20_000)
                .mapToObj(index -> new MethodCallEdge(
                        target.methodKey(), target.methodKey(), index + 1))
                .toList();
        MethodCatalog large = new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, CASE_ID, ANALYSIS_ID, TARGET,
                List.of(target), edges, List.of(),
                SnapshotCompleteness.COMPLETE,
                1, edges.size(), TIME.plusSeconds(3));

        Path document = repository.createMethodCatalog(large);

        assertTrue(Files.size(document) > BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
        assertEquals(large, repository.requireMethodCatalog(CASE_ID, ANALYSIS_ID));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class, () -> repository.createMethodCatalog(large)).code());
        try (var files = Files.list(document.getParent())) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void shouldRejectPlanSelectorThatDoesNotExactlyMatchCatalogAnchor() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        MethodSelector unknown = new MethodSelector(
                "a.b.ScheduleTest#other()V", "a.b.ScheduleTest", "other", "()V");
        CodePathCollectionPlan plan = planWithSelectors(List.of(unknown));

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> repository.createCodePathPlan(plan));

        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", failure.code());
    }

    @Test
    void shouldRejectDuplicatePlanSelectorsBeforeArchiving() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        SourceAnchor anchor = methodCatalog().entries().getFirst().sourceAnchor();
        MethodSelector selector = new MethodSelector(
                "a.b.ScheduleTest#case1()V", anchor.className(), anchor.methodName(),
                anchor.descriptor());
        assertThrows(IllegalArgumentException.class,
                () -> planWithSelectors(List.of(selector, selector)));
    }

    @Test
    void shouldCreateOneAppendOnlyCollectionDirectory() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        repository.createCodePathPlan(codePathPlan());
        MethodPathCollectionRecord record = new MethodPathCollectionRecord(
                "1.0", CASE_ID, ANALYSIS_ID, new RunId("run-codepath-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"), TARGET,
                "CODEPATH", TIME.plusSeconds(5));

        Path collection = repository.startMethodPathCollection(record);

        assertTrue(Files.isRegularFile(collection.resolve("collection-request.json")));
        assertFalse(Files.exists(collection.resolve("raw")));
        assertFalse(Files.exists(collection.resolve("derived")));
        assertFalse(Files.exists(collection.resolve("logs")));
        assertEquals(record, repository.requireMethodPathCollection(
                CASE_ID, new CollectionId("collection-1")));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(WorkspaceException.class,
                () -> repository.startMethodPathCollection(record)).code());
    }

    @Test
    void shouldArchiveJdwpPlanAndCollectionUnderOwningAnalysis() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        JdwpCollectionPlan plan = jdwpPlan(ANALYSIS_ID);

        Path planPath = repository.createJdwpPlan(plan);
        JdwpCollectionRecord record = new JdwpCollectionRecord(
                SchemaVersions.JDWP_COLLECTION_REQUEST, CASE_ID, ANALYSIS_ID,
                new RunId("run-jdwp-1"), plan.planId(), new CollectionId("jdwp-1"),
                TARGET, "JDWP", TIME.plusSeconds(5));
        Path collection = repository.startJdwpCollection(record);

        assertEquals(plan, repository.requireJdwpPlan(CASE_ID, ANALYSIS_ID, plan.planId()));
        assertEquals(record, repository.requireJdwpCollection(CASE_ID, record.collectionId()));
        assertTrue(planPath.endsWith("analyses/analysis-1/plans/jdwp-plan-1.json"));
        assertTrue(Files.isRegularFile(collection.resolve("collection-request.json")));
        assertFalse(Files.exists(collection.resolve("raw")));
        assertFalse(Files.exists(collection.resolve("logs")));
        assertFalse(Files.exists(collection.resolve("validation")));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class, () -> repository.createJdwpPlan(plan)).code());
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class, () -> repository.startJdwpCollection(record)).code());
    }

    @Test
    void shouldRejectJdwpPlanAndCollectionWithCrossAnalysisIdentity() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        AnalysisId otherAnalysis = new AnalysisId("analysis-2");
        JdwpCollectionPlan wrongPlan = jdwpPlan(otherAnalysis);

        assertEquals("METHOD_CATALOG_NOT_FOUND", assertThrows(
                WorkspaceException.class, () -> repository.createJdwpPlan(wrongPlan)).code());

        JdwpCollectionPlan plan = jdwpPlan(ANALYSIS_ID);
        repository.createJdwpPlan(plan);
        JdwpCollectionRecord wrongRecord = new JdwpCollectionRecord(
                SchemaVersions.JDWP_COLLECTION_REQUEST, CASE_ID, otherAnalysis,
                new RunId("run-jdwp-2"), plan.planId(), new CollectionId("jdwp-2"),
                TARGET, "JDWP", TIME.plusSeconds(6));
        assertEquals("JDWP_PLAN_NOT_FOUND", assertThrows(
                WorkspaceException.class, () -> repository.startJdwpCollection(wrongRecord)).code());
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
    void shouldCreateRunFingerprintOnceAndValidateRunIdentity() {
        prepareRun(run(new RunId("run-1"), TIME.plusSeconds(3)));
        RunResultFingerprint fingerprint = ganttFingerprint(
                ANALYSIS_ID, new RunId("run-1"), "a", "b");

        Path created = repository.createRunResultFingerprint(fingerprint);

        assertEquals(
                temporaryDirectory.resolve(
                        "cases/case-1/runs/run-1/run-result-fingerprint.json"),
                created);
        WorkspaceException overwrite = assertThrows(
                WorkspaceException.class,
                () -> repository.createRunResultFingerprint(fingerprint));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", overwrite.code());

        AnalysisId wrongAnalysis = new AnalysisId("analysis-2");
        WorkspaceException mismatch = assertThrows(
                WorkspaceException.class,
                () -> repository.createRunResultFingerprint(ganttFingerprint(
                        wrongAnalysis, new RunId("run-1"), "a", "b")));
        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", mismatch.code());
    }





    @Test
    void layoutRejectsOpaqueIdsThatWouldEscapeArchiveRoots() {
        Path casesRoot = temporaryDirectory.resolve("cases");

        assertThrows(IllegalArgumentException.class,
                () -> CaseArchiveLayout.of(casesRoot, new CaseId("../outside")));
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, CASE_ID);
        assertThrows(IllegalArgumentException.class,
                () -> layout.runResultFingerprint(new RunId("../outside")));
    }

    static CaseManifest manifest() {
        return new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET,
                "wafer-demo", "为什么有空闲？", TIME);
    }


    static AnalysisRequest analysis() {
        return new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, ANALYSIS_ID,
                "继续分析空闲", TIME.plusSeconds(2));
    }

    static RunRequest run(RunId runId, Instant createdAt) {
        return new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, ANALYSIS_ID,
                runId, TARGET, "UNINSTRUMENTED", createdAt);
    }

    private void prepareRun(RunRequest request) {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        repository.startRun(request);
    }



    private static RunResultFingerprint ganttFingerprint(
            AnalysisId analysisId,
            RunId runId,
            String rawSeed,
            String normalizedSeed) {
        return new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, analysisId, runId,
                normalizedSeed.repeat(64));
    }

    static MethodCatalog methodCatalog() {
        MethodCatalogEntry entry = new MethodCatalogEntry(
                "a.b.ScheduleTest#case1()V",
                new SourceAnchor("a.b.ScheduleTest", "case1", "()V",
                        "src/test/java/a/b/ScheduleTest.java", 1, 2),
                0, true);
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, CASE_ID, ANALYSIS_ID, TARGET,
                List.of(entry), List.of(), List.of(),
                SnapshotCompleteness.COMPLETE,
                1, 0, TIME.plusSeconds(3));
    }

    static CodePathCollectionPlan codePathPlan() {
        SourceAnchor anchor = methodCatalog().entries().getFirst().sourceAnchor();
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"), CASE_ID,
                ANALYSIS_ID, TARGET,
                List.of(new org.example.algorithmdebug.contracts.CodePathMethodSelection(
                        new MethodSelector(
                                "a.b.ScheduleTest#case1()V", anchor.className(), anchor.methodName(),
                                anchor.descriptor()),
                        List.of())),
                java.util.Optional.empty(), CollectionBudget.defaults(), "定位",
                new org.example.algorithmdebug.contracts.InvestigationIntent(
                        "Which path executed?", "The selected method executed", List.of(),
                        List.of("Observed method path")),
                TIME.plusSeconds(4));
    }

    private static CodePathCollectionPlan planWithSelectors(List<MethodSelector> selectors) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-selector-check"), CASE_ID,
                ANALYSIS_ID, TARGET, selectors.stream()
                        .map(selector -> new org.example.algorithmdebug.contracts.CodePathMethodSelection(
                                selector, List.of()))
                        .toList(),
                java.util.Optional.empty(), CollectionBudget.defaults(), "定位",
                new org.example.algorithmdebug.contracts.InvestigationIntent(
                        "Which path executed?", "The selected method executed", List.of(),
                        List.of("Observed method path")),
                TIME.plusSeconds(4));
    }

    private static JdwpCollectionPlan jdwpPlan(AnalysisId analysisId) {
        SourceAnchor anchor = methodCatalog().entries().getFirst().sourceAnchor();
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN, new PlanId("jdwp-plan-1"), CASE_ID,
                analysisId, TARGET,
                List.of(new JdwpTracepointSpec(
                        "schedule-entry", methodCatalog().entries().getFirst().methodKey(),
                        anchor, 1, 3, 3, 3, 0, null, JdwpCaptureSpec.stackOnly())),
                JdwpCollectionBudget.defaults(), "Inspect scheduler method", new org.example.algorithmdebug.contracts.InvestigationIntent("Which state was observed?", "The target method receives the expected state", List.of(), List.of("A matching runtime snapshot")), TIME.plusSeconds(4));
    }
}
