package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.ArtifactReference;
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
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.investigation.SourceQueryBudget;
import org.example.algorithmdebug.contracts.investigation.SourceQueryCompleteness;
import org.example.algorithmdebug.contracts.investigation.SourceQueryId;
import org.example.algorithmdebug.contracts.investigation.SourceQueryMode;
import org.example.algorithmdebug.contracts.investigation.SourceQueryRequest;
import org.example.algorithmdebug.contracts.investigation.SourceQueryResult;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.example.algorithmdebug.contracts.investigation.EvidenceGap;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapStatus;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.HypothesisRecord;
import org.example.algorithmdebug.contracts.investigation.HypothesisStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;
import org.example.algorithmdebug.contracts.investigation.InvestigationBindingStatus;
import org.example.algorithmdebug.contracts.investigation.InvestigationEvent;
import org.example.algorithmdebug.contracts.investigation.ObservationOperator;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;
import org.example.algorithmdebug.contracts.investigation.ObservationSelector;
import org.example.algorithmdebug.contracts.investigation.PredicateRole;
import org.example.algorithmdebug.contracts.investigation.ProblemFrame;
import org.example.algorithmdebug.contracts.investigation.ProblemFrameId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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
        Path raw = repository.caseRoot(CASE_ID).resolve("runs/run-1/raw");
        assertFalse(Files.exists(raw));
        assertEquals(raw, repository.runRawDirectory(CASE_ID, run.runId()));
        assertTrue(Files.isDirectory(raw));
    }

    @Test
    void shouldRemoveNewCaseDirectoriesWhenTheInitialAtomicWriteFails() {
        AtomicDocumentWriter failingWriter = new AtomicDocumentWriter((source, target) -> {
            throw new java.io.IOException("simulated move failure");
        });
        CaseArchiveRepository failingRepository = new CaseArchiveRepository(
                repository.caseRoot(CASE_ID).getParent(),
                new BoundedDocumentMapper(), failingWriter);

        assertThrows(WorkspaceException.class, () -> failingRepository.createCase(manifest()));

        assertFalse(Files.exists(failingRepository.caseRoot(CASE_ID)));
    }

    @Test
    void shouldArchiveMethodCatalogAndPlanOnceWithMatchingIdentity() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        MethodCatalog catalog = methodCatalog();
        CodePathCollectionPlan plan = codePathPlan();
        archiveInvestigation(repository);

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
    void shouldRejectLegacyPlanCreationAndUnknownPredicateBinding() {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        repository.createMethodCatalog(methodCatalog());
        CodePathCollectionPlan current = codePathPlan();
        CodePathCollectionPlan legacy = new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN_LEGACY,
                new PlanId("legacy-plan"), current.caseId(), current.analysisId(),
                current.targetTest(), current.methodSelections(), current.scopeMethodKey(),
                current.scopeConditions(), current.captureMode(), current.scopeStartOrdinal(),
                current.maxMatchedScopes(), current.budget(), current.rationale(),
                new org.example.algorithmdebug.contracts.InvestigationIntent(
                        current.questionToAnswer(), "old hypothesis", List.of(),
                        List.of("old observation")), current.createdAt());

        WorkspaceException legacyFailure = assertThrows(
                WorkspaceException.class, () -> repository.createCodePathPlan(legacy));
        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", legacyFailure.code());

        archiveInvestigation(repository);
        InvestigationBinding unknown = new InvestigationBinding(
                SchemaVersions.INVESTIGATION_BINDING, CASE_ID, ANALYSIS_ID,
                new EvidenceGapId("gap-1"), List.of(new HypothesisId("hypothesis-1")),
                List.of(new ObservationPredicateId("predicate-missing")), List.of());
        CodePathCollectionPlan invalid = new CodePathCollectionPlan(
                current.schemaVersion(), new PlanId("unknown-predicate"), current.caseId(),
                current.analysisId(), current.targetTest(), current.methodSelections(),
                current.scopeMethodKey(), current.scopeConditions(), current.captureMode(),
                current.scopeStartOrdinal(), current.maxMatchedScopes(), current.budget(),
                current.rationale(), current.questionToAnswer(),
                InvestigationBindingStatus.STRUCTURED, Optional.of(unknown), current.createdAt());

        WorkspaceException predicateFailure = assertThrows(
                WorkspaceException.class, () -> repository.createCodePathPlan(invalid));
        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", predicateFailure.code());

        ObservationPredicateId corroboratingId =
                new ObservationPredicateId("predicate-corroborating");
        appendPredicate(repository, corroboratingId, PredicateRole.CORROBORATING, 5);
        InvestigationBinding withoutCritical = new InvestigationBinding(
                SchemaVersions.INVESTIGATION_BINDING, CASE_ID, ANALYSIS_ID,
                new EvidenceGapId("gap-1"), List.of(new HypothesisId("hypothesis-1")),
                List.of(corroboratingId), List.of());
        CodePathCollectionPlan insufficient = new CodePathCollectionPlan(
                current.schemaVersion(), new PlanId("without-critical"), current.caseId(),
                current.analysisId(), current.targetTest(), current.methodSelections(),
                current.scopeMethodKey(), current.scopeConditions(), current.captureMode(),
                current.scopeStartOrdinal(), current.maxMatchedScopes(), current.budget(),
                current.rationale(), current.questionToAnswer(),
                InvestigationBindingStatus.STRUCTURED, Optional.of(withoutCritical),
                current.createdAt());

        WorkspaceException criticalFailure = assertThrows(
                WorkspaceException.class, () -> repository.createCodePathPlan(insufficient));
        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", criticalFailure.code());
    }

    @Test
    void shouldArchiveSourceQueryRequestAndResultOnceWithMatchingProvenance() throws Exception {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        Path catalogPath = repository.createMethodCatalog(methodCatalog());
        ArtifactReference catalogArtifact = registerCatalog(catalogPath);
        SourceQueryRequest request = sourceQueryRequest(catalogArtifact);
        SourceQueryResult result = sourceQueryResult(request, SourceQueryMode.METHOD);

        Path requestPath = repository.createSourceQueryRequest(request);
        Path resultPath = repository.createSourceQueryResult(result);

        assertEquals(request, repository.requireSourceQueryRequest(
                CASE_ID, ANALYSIS_ID, request.queryId()));
        assertEquals(result, repository.requireSourceQueryResult(
                CASE_ID, ANALYSIS_ID, request.queryId()));
        assertTrue(requestPath.endsWith(
                "analyses/analysis-1/source-queries/query-1/request.json"));
        assertTrue(resultPath.endsWith(
                "analyses/analysis-1/source-queries/query-1/result.json"));
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class,
                () -> repository.createSourceQueryRequest(request)).code());
        assertEquals("CASE_ARCHIVE_WRITE_FAILED", assertThrows(
                WorkspaceException.class,
                () -> repository.createSourceQueryResult(result)).code());
    }

    @Test
    void shouldRejectSourceQueryWithUnregisteredCatalogOrMismatchedResult() throws Exception {
        repository.createCase(manifest());
        repository.createAnalysis(analysis());
        Path catalogPath = repository.createMethodCatalog(methodCatalog());
        ArtifactReference catalogArtifact = registerCatalog(catalogPath);
        ArtifactReference unregistered = new ArtifactReference(
                "catalog-unregistered", "METHOD_CATALOG", catalogArtifact.relativePath(),
                catalogArtifact.mediaType(), catalogArtifact.sha256(), catalogArtifact.sizeBytes());

        WorkspaceException missingRegistration = assertThrows(
                WorkspaceException.class,
                () -> repository.createSourceQueryRequest(sourceQueryRequest(unregistered)));
        assertEquals("CASE_ARTIFACT_NOT_REGISTERED", missingRegistration.code());

        SourceQueryRequest request = sourceQueryRequest(catalogArtifact);
        repository.createSourceQueryRequest(request);
        WorkspaceException mismatch = assertThrows(
                WorkspaceException.class,
                () -> repository.createSourceQueryResult(
                        sourceQueryResult(request, SourceQueryMode.CALLERS)));
        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", mismatch.code());

        SourceQueryResult oversized = new SourceQueryResult(
                SchemaVersions.SOURCE_QUERY_RESULT,
                request.queryId(), request.caseId(), request.analysisId(), request.mode(),
                SourceQueryCompleteness.COMPLETE, request.methodCatalogArtifact(),
                List.of(methodCatalog().entries().getFirst()), List.of(), List.of(),
                List.of(new SourceQueryResult.SourceWindow(
                        methodCatalog().entries().getFirst().sourceAnchor(),
                        1, 1, "a".repeat(64), "x".repeat(70_000))),
                List.of(), false, TIME.plusSeconds(6));
        WorkspaceException overBudget = assertThrows(
                WorkspaceException.class,
                () -> repository.createSourceQueryResult(oversized));
        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", overBudget.code());
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
        archiveInvestigation(repository);
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
        archiveInvestigation(repository);
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
        archiveInvestigation(repository);
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
    void shouldNeverWriteLegacyCollectionExecutionSummary() {
        CollectionExecutionSummary legacy = CollectionExecutionSummary.fromJson(
                null, CASE_ID, ANALYSIS_ID, new RunId("run-legacy"),
                new PlanId("plan-legacy"), new CollectionId("collection-legacy"),
                "SUCCESS", org.example.algorithmdebug.contracts.ComparisonOutcome.NOT_COMPARED,
                null, true, List.of("manifest.json"), List.of(), null, null, null,
                null, null, null);

        WorkspaceException failure = assertThrows(WorkspaceException.class,
                () -> repository.createCollectionExecutionSummary(legacy));

        assertEquals("COLLECTION_SUMMARY_VERSION_UNSUPPORTED", failure.code());
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

    private ArtifactReference registerCatalog(Path catalogPath) throws Exception {
        ArtifactReference artifact = new ArtifactReference(
                "analysis-1-method-catalog", "METHOD_CATALOG",
                repository.caseRoot(CASE_ID).relativize(catalogPath)
                        .toString().replace('\\', '/'),
                "application/json",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(catalogPath))),
                Files.size(catalogPath));
        repository.registerArtifact(CASE_ID, artifact, TIME.plusSeconds(4));
        return artifact;
    }

    private static SourceQueryRequest sourceQueryRequest(ArtifactReference catalogArtifact) {
        return new SourceQueryRequest(
                SchemaVersions.SOURCE_QUERY_REQUEST,
                new SourceQueryId("query-1"),
                CASE_ID,
                ANALYSIS_ID,
                catalogArtifact,
                SourceQueryMode.METHOD,
                Optional.of(methodCatalog().entries().getFirst().methodKey()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                SourceQueryBudget.defaults(),
                TIME.plusSeconds(5));
    }

    private static SourceQueryResult sourceQueryResult(
            SourceQueryRequest request, SourceQueryMode mode) {
        return new SourceQueryResult(
                SchemaVersions.SOURCE_QUERY_RESULT,
                request.queryId(),
                request.caseId(),
                request.analysisId(),
                mode,
                SourceQueryCompleteness.COMPLETE,
                request.methodCatalogArtifact(),
                List.of(methodCatalog().entries().getFirst()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                TIME.plusSeconds(6));
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
                Optional.empty(), List.of(),
                org.example.algorithmdebug.contracts.CodePathCaptureMode.TRACE, 1, 10_000,
                CollectionBudget.defaults(), "定位", "Which path executed?",
                InvestigationBindingStatus.STRUCTURED,
                Optional.of(binding(ANALYSIS_ID)),
                TIME.plusSeconds(4));
    }

    private static CodePathCollectionPlan planWithSelectors(List<MethodSelector> selectors) {
        return new CodePathCollectionPlan(
                SchemaVersions.CODEPATH_COLLECTION_PLAN, new PlanId("plan-selector-check"), CASE_ID,
                ANALYSIS_ID, TARGET, selectors.stream()
                        .map(selector -> new org.example.algorithmdebug.contracts.CodePathMethodSelection(
                                selector, List.of()))
                        .toList(),
                Optional.empty(), List.of(),
                org.example.algorithmdebug.contracts.CodePathCaptureMode.TRACE, 1, 10_000,
                CollectionBudget.defaults(), "定位", "Which path executed?",
                InvestigationBindingStatus.STRUCTURED,
                Optional.of(binding(ANALYSIS_ID)),
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
                JdwpCollectionBudget.defaults(), "Inspect scheduler method",
                "Which state was observed?", InvestigationBindingStatus.STRUCTURED,
                Optional.of(binding(analysisId)), TIME.plusSeconds(4));
    }

    private static InvestigationBinding binding(AnalysisId analysisId) {
        return new InvestigationBinding(
                SchemaVersions.INVESTIGATION_BINDING, CASE_ID, analysisId,
                new EvidenceGapId("gap-1"),
                List.of(new HypothesisId("hypothesis-1")),
                List.of(new ObservationPredicateId("predicate-1")), List.of());
    }

    static void archiveInvestigation(CaseArchiveRepository repository) {
        SourceAnchor anchor = methodCatalog().entries().getFirst().sourceAnchor();
        HypothesisId hypothesisId = new HypothesisId("hypothesis-1");
        EvidenceGapId gapId = new EvidenceGapId("gap-1");
        ObservationPredicateId predicateId = new ObservationPredicateId("predicate-1");
        ProblemFrame frame = new ProblemFrame(
                SchemaVersions.PROBLEM_FRAME, new ProblemFrameId("frame-1"),
                CASE_ID, ANALYSIS_ID, "unexpected scheduler result", "expected result",
                "actual result", TARGET, List.of(anchor), List.of("run:run-1"),
                List.of("which path executed"), TIME);
        HypothesisRecord hypothesis = new HypothesisRecord(
                SchemaVersions.HYPOTHESIS_RECORD, hypothesisId, CASE_ID, ANALYSIS_ID,
                "the selected method produced the result", HypothesisStatus.OPEN,
                List.of(anchor), List.of(), List.of(), List.of(gapId), TIME);
        EvidenceGap gap = new EvidenceGap(
                SchemaVersions.EVIDENCE_GAP, gapId, CASE_ID, ANALYSIS_ID,
                "which path produced the result", EvidenceGapStatus.OPEN,
                List.of(hypothesisId), List.of(predicateId), TIME);
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, predicateId, CASE_ID, ANALYSIS_ID,
                hypothesisId, gapId, ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches(), PredicateRole.CRITICAL,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, TIME);
        InvestigationEventArchive events = new InvestigationEventArchive(
                repository.casesRoot(), repository.mapper(), new AtomicDocumentWriter());
        AnalysisIdentity identity = new AnalysisIdentity(PROJECT_ID, CASE_ID, ANALYSIS_ID);
        events.appendEvent(identity, new InvestigationEvent.ProblemFrameDefined(
                SchemaVersions.INVESTIGATION_EVENT, "event-frame", CASE_ID, ANALYSIS_ID,
                1, TIME, frame));
        events.appendEvent(identity, new InvestigationEvent.HypothesisAdded(
                SchemaVersions.INVESTIGATION_EVENT, "event-hypothesis", CASE_ID, ANALYSIS_ID,
                2, TIME.plusSeconds(1), hypothesis));
        events.appendEvent(identity, new InvestigationEvent.EvidenceGapAdded(
                SchemaVersions.INVESTIGATION_EVENT, "event-gap", CASE_ID, ANALYSIS_ID,
                3, TIME.plusSeconds(2), gap));
        events.appendEvent(identity, new InvestigationEvent.PredicateRegistered(
                SchemaVersions.INVESTIGATION_EVENT, "event-predicate", CASE_ID, ANALYSIS_ID,
                4, TIME.plusSeconds(3), predicate));
    }

    private static void appendPredicate(
            CaseArchiveRepository repository,
            ObservationPredicateId predicateId,
            PredicateRole role,
            long sequence) {
        ObservationPredicate predicate = new ObservationPredicate(
                SchemaVersions.OBSERVATION_PREDICATE, predicateId, CASE_ID, ANALYSIS_ID,
                new HypothesisId("hypothesis-1"), new EvidenceGapId("gap-1"),
                ObservationOperator.FAILURE_FINGERPRINT_MATCHES,
                new ObservationSelector.FailureFingerprintMatches(), role,
                HypothesisEffect.SUPPORT, HypothesisEffect.REFUTE,
                HypothesisEffect.NO_CHANGE, TIME.plusSeconds(sequence - 1));
        InvestigationEventArchive events = new InvestigationEventArchive(
                repository.casesRoot(), repository.mapper(), new AtomicDocumentWriter());
        events.appendEvent(
                new AnalysisIdentity(PROJECT_ID, CASE_ID, ANALYSIS_ID),
                new InvestigationEvent.PredicateRegistered(
                        SchemaVersions.INVESTIGATION_EVENT,
                        "event-" + predicateId.value(), CASE_ID, ANALYSIS_ID,
                        sequence, TIME.plusSeconds(sequence - 1), predicate));
    }
}
