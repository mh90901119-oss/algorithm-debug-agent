package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextRecord;
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
        ContextRecord context = context();
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
    void shouldArchiveMethodCatalogAndPlanOnceWithMatchingIdentity() {
        repository.createCase(manifest());
        repository.createContext(context());
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
        repository.createContext(context());
        repository.createAnalysis(analysis());
        MethodCatalogEntry target = methodCatalog().entries().getFirst();
        List<MethodCallEdge> edges = IntStream.range(0, 20_000)
                .mapToObj(index -> new MethodCallEdge(
                        target.methodKey(), target.methodKey(), index + 1))
                .toList();
        MethodCatalog large = new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, CASE_ID, CONTEXT_ID, ANALYSIS_ID, TARGET,
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
        repository.createContext(context());
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
        repository.createContext(context());
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
        repository.createContext(context());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        repository.createCodePathPlan(codePathPlan());
        MethodPathCollectionRecord record = new MethodPathCollectionRecord(
                "1.0", CASE_ID, CONTEXT_ID, ANALYSIS_ID, new RunId("run-codepath-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"), TARGET,
                "CODEPATH", TIME.plusSeconds(5));

        Path collection = repository.startMethodPathCollection(record);

        assertTrue(Files.isDirectory(collection.resolve("raw")));
        assertTrue(Files.isDirectory(collection.resolve("derived")));
        assertTrue(Files.isDirectory(collection.resolve("logs")));
        assertEquals(record, repository.requireMethodPathCollection(
                CASE_ID, new CollectionId("collection-1")));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(WorkspaceException.class,
                () -> repository.startMethodPathCollection(record)).code());
    }

    @Test
    void shouldArchiveJdwpPlanAndCollectionUnderOwningAnalysis() {
        repository.createCase(manifest());
        repository.createContext(context());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        JdwpCollectionPlan plan = jdwpPlan(ANALYSIS_ID, CONTEXT_ID);

        Path planPath = repository.createJdwpPlan(plan);
        JdwpCollectionRecord record = new JdwpCollectionRecord(
                SchemaVersions.JDWP_COLLECTION_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                new RunId("run-jdwp-1"), plan.planId(), new CollectionId("jdwp-1"),
                TARGET, "JDWP", TIME.plusSeconds(5));
        Path collection = repository.startJdwpCollection(record);

        assertEquals(plan, repository.requireJdwpPlan(CASE_ID, ANALYSIS_ID, plan.planId()));
        assertEquals(record, repository.requireJdwpCollection(CASE_ID, record.collectionId()));
        assertTrue(planPath.endsWith("analyses/analysis-1/plans/jdwp-plan-1.json"));
        assertTrue(Files.isDirectory(collection.resolve("raw")));
        assertTrue(Files.isDirectory(collection.resolve("logs")));
        assertTrue(Files.isDirectory(collection.resolve("validation")));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class, () -> repository.createJdwpPlan(plan)).code());
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class, () -> repository.startJdwpCollection(record)).code());
    }

    @Test
    void shouldRejectJdwpPlanAndCollectionWithCrossAnalysisIdentity() {
        repository.createCase(manifest());
        repository.createContext(context());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        AnalysisId otherAnalysis = new AnalysisId("analysis-2");
        JdwpCollectionPlan wrongPlan = jdwpPlan(otherAnalysis, CONTEXT_ID);

        assertEquals("METHOD_CATALOG_NOT_FOUND", assertThrows(
                WorkspaceException.class, () -> repository.createJdwpPlan(wrongPlan)).code());

        JdwpCollectionPlan plan = jdwpPlan(ANALYSIS_ID, CONTEXT_ID);
        repository.createJdwpPlan(plan);
        JdwpCollectionRecord wrongRecord = new JdwpCollectionRecord(
                SchemaVersions.JDWP_COLLECTION_REQUEST, CASE_ID, CONTEXT_ID, otherAnalysis,
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
    void shouldRejectContextWhoseCaseDoesNotExist() {
        repository.createCase(manifest());
        ContextRecord wrong = new ContextRecord(
                SchemaVersions.CONTEXT_RECORD, new CaseId("case-2"), CONTEXT_ID, TIME);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> repository.createContext(wrong));

        assertEquals("CASE_NOT_FOUND", failure.code());
    }

    @Test
    void shouldRejectRunWhoseAnalysisBelongsToAnotherContext() {
        repository.createCase(manifest());
        repository.createContext(context());
        repository.createAnalysis(analysis());
        ContextId secondContextId = new ContextId("context-2");
        repository.createContext(new ContextRecord(
                SchemaVersions.CONTEXT_RECORD, CASE_ID, secondContextId, TIME.plusSeconds(3)));
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
        repository.createContext(context(currentId, TIME.plusSeconds(2)));

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
        repository.createContext(context(currentId, TIME.plusSeconds(3)));
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
                "wafer-demo", "为什么有空闲？", TIME);
    }

    static ContextRecord context() {
        return new ContextRecord(
                SchemaVersions.CONTEXT_RECORD, CASE_ID, CONTEXT_ID, TIME.plusSeconds(1));
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
        repository.createContext(context(contextId, createdAt));
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

    private static ContextRecord context(ContextId contextId, Instant createdAt) {
        return new ContextRecord(
                SchemaVersions.CONTEXT_RECORD, CASE_ID, contextId, createdAt);
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

    static MethodCatalog methodCatalog() {
        MethodCatalogEntry entry = new MethodCatalogEntry(
                "a.b.ScheduleTest#case1()V",
                new SourceAnchor("a.b.ScheduleTest", "case1", "()V",
                        "src/test/java/a/b/ScheduleTest.java", 1, 2, "a".repeat(64)),
                0, true);
        return new MethodCatalog(
                SchemaVersions.METHOD_CATALOG, CASE_ID, CONTEXT_ID, ANALYSIS_ID, TARGET,
                List.of(entry), List.of(), List.of(),
                SnapshotCompleteness.COMPLETE,
                1, 0, TIME.plusSeconds(3));
    }

    static CodePathCollectionPlan codePathPlan() {
        SourceAnchor anchor = methodCatalog().entries().getFirst().sourceAnchor();
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-1"), CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, TARGET,
                List.of(new MethodSelector(
                        "a.b.ScheduleTest#case1()V", anchor.className(), anchor.methodName(),
                        anchor.descriptor())),
                CollectionBudget.defaults(), "定位", TIME.plusSeconds(4));
    }

    private static CodePathCollectionPlan planWithSelectors(List<MethodSelector> selectors) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-selector-check"), CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, TARGET, selectors,
                CollectionBudget.defaults(), "定位", TIME.plusSeconds(4));
    }

    private static JdwpCollectionPlan jdwpPlan(
            AnalysisId analysisId, ContextId contextId) {
        SourceAnchor anchor = methodCatalog().entries().getFirst().sourceAnchor();
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN, new PlanId("jdwp-plan-1"), CASE_ID,
                contextId, analysisId, TARGET,
                List.of(new JdwpTracepointSpec(
                        "schedule-entry", methodCatalog().entries().getFirst().methodKey(),
                        anchor, 1, 3, JdwpCaptureSpec.stackOnly())),
                JdwpCollectionBudget.defaults(), "检查调度方法", TIME.plusSeconds(4));
    }
}
