package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.CollectionValidation;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceBundle;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.EvidenceFact;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.EvidenceValidationStatus;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.NormalizationBudget;
import org.example.algorithmdebug.contracts.NormalizationManifest;
import org.example.algorithmdebug.contracts.NormalizationStatus;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.FailureCategory;
import org.example.algorithmdebug.contracts.TargetFailureDiagnostic;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SufficiencyEvaluation;
import org.example.algorithmdebug.contracts.SufficiencyStatus;
import org.example.algorithmdebug.contracts.TraceProvenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseEvidenceArchiveTest {

    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final CollectionId COLLECTION_ID = new CollectionId("collection-1");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-1");
    private static final RunId RUN_ID = new RunId("run-1");
    private static final PlanId PLAN_ID = new PlanId("plan-1");
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private Path casesRoot;
    private CaseArchiveRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        casesRoot = temporaryDirectory.resolve("cases");
        Files.createDirectories(casesRoot);
        repository = new CaseArchiveRepository(
                casesRoot, new BoundedDocumentMapper(), new AtomicDocumentWriter());
        repository.createCase(CaseArchiveRepositoryTest.manifest());
        repository.createContext(CaseArchiveRepositoryTest.context());
        repository.createAnalysis(CaseArchiveRepositoryTest.analysis());
        repository.startRun(CaseArchiveRepositoryTest.run(RUN_ID, NOW));
        repository.completeRun(new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID, RUN_ID,
                ProcessOutcome.FAILED, TestOutcome.ERROR, GanttOutcome.ABSENT,
                Optional.of(new TargetFailureDiagnostic(
                        FailureCategory.TEST_ERROR, "java.lang.IllegalStateException",
                        "no feasible result", "", "fixture.Algorithm.solve")),
                Optional.empty(), ComparisonOutcome.NOT_COMPARED, "not compared", List.of()));
        repository.createMethodCatalog(CaseArchiveRepositoryTest.methodCatalog());
        repository.createCodePathPlan(CaseArchiveRepositoryTest.codePathPlan());
        repository.startMethodPathCollection(new MethodPathCollectionRecord(
                "1.0", CASE_ID, CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID,
                RUN_ID, PLAN_ID, COLLECTION_ID, CaseArchiveRepositoryTest.manifest().targetTest(),
                "CODEPATH", NOW));
    }

    @Test
    void derivesExactAppendOnlyEvidencePaths() {
        CaseArchiveLayout layout = CaseArchiveLayout.of(casesRoot, CASE_ID);

        assertEquals(
                layout.collectionRoot(COLLECTION_ID)
                        .resolve("derived/evidence-1/normalization-manifest.json"),
                layout.normalizationManifest(COLLECTION_ID, EVIDENCE_ID));
        assertEquals(
                layout.collectionRoot(COLLECTION_ID)
                        .resolve("derived/evidence-1/method-path-summary.json"),
                layout.methodPathSummary(COLLECTION_ID, EVIDENCE_ID));
        assertEquals(
                layout.evidenceRoot().resolve("evidence-1/evidence-bundle.json"),
                layout.evidenceBundle(EVIDENCE_ID));
    }

    @Test
    void archivesEvidenceDocumentsOnceAndReadsThemByPortablePath() {
        EvidenceBuildRequest request = request();
        MethodPathSummary summary = summary();
        NormalizationManifest manifest = manifest(summaryArtifact());
        CollectionValidation validation = validation();
        EvidenceBundle bundle = bundle();
        SufficiencyEvaluation sufficiency = sufficiency();

        Path requestPath = repository.createEvidenceRequest(request);
        repository.createMethodPathSummary(summary);
        repository.createNormalizationManifest(manifest);
        repository.createCollectionValidation(validation);
        repository.createEvidenceBundle(bundle);
        repository.createSufficiencyEvaluation(sufficiency);

        assertEquals(request, repository.requireEvidenceRequest(CASE_ID, EVIDENCE_ID));
        assertEquals(bundle, repository.requireEvidenceBundle(CASE_ID, EVIDENCE_ID));
        assertTrue(Files.isRegularFile(requestPath));
        assertThrows(WorkspaceException.class, () -> repository.createEvidenceRequest(request));
    }

    @Test
    void artifactAccessRejectsEscapeAndDescribesOnlyCaseRelativeFiles() throws Exception {
        CaseArtifactAccess access = new CaseArtifactAccess(casesRoot);
        Path raw = repository.layout(CASE_ID).collectionRoot(COLLECTION_ID)
                .resolve("raw/filtered.jsonl");
        Files.createDirectories(raw.getParent());
        Files.writeString(raw, "{}\n");

        Path resolved = access.requireRegularArtifact(
                CASE_ID, "collections/collection-1/raw/filtered.jsonl", 1024);
        ArtifactReference reference = access.describe(
                CASE_ID, "raw-1", "CODEPATH_FILTERED_TRACE",
                "application/x-ndjson", resolved);

        assertEquals("collections/collection-1/raw/filtered.jsonl", reference.relativePath());
        assertEquals(3, reference.sizeBytes());
        assertThrows(WorkspaceException.class, () -> access.requireRegularArtifact(
                CASE_ID, "../outside.json", 1024));
    }

    @Test
    void rejectsDerivedSummaryWhoseRunDoesNotMatchCollectionRequest() {
        repository.createEvidenceRequest(request());
        MethodPathSummary mismatched = new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID,
                new RunId("run-other"), PLAN_ID, COLLECTION_ID, rawArtifact(),
                List.of(), List.of(), List.of(), false, NOW);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> repository.createMethodPathSummary(mismatched));

        assertEquals("CASE_ARCHIVE_IDENTITY_MISMATCH", failure.code());
    }

    @Test
    void rejectsMethodPathSummaryBeyondFourMiBHardLimit() {
        repository.createEvidenceRequest(request());
        TraceProvenance provenance = new TraceProvenance(
                CASE_ID, CaseArchiveRepositoryTest.context().contextId(), RUN_ID,
                COLLECTION_ID, rawArtifact(), 1, Optional.of(1L), Optional.empty(),
                "RAW_OBSERVATION");
        List<MethodPathSummary.PathAnomaly> anomalies = IntStream.range(0, 2_500)
                .mapToObj(index -> new MethodPathSummary.PathAnomaly(
                        "ANOMALY_" + index, "x".repeat(2_000), provenance))
                .toList();
        MethodPathSummary oversized = new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID, RUN_ID,
                PLAN_ID, COLLECTION_ID, rawArtifact(),
                List.of(), List.of(), anomalies, false, NOW);

        assertThrows(WorkspaceException.class,
                () -> repository.createMethodPathSummary(oversized));
    }

    @Test
    void permitsSameContextCollectionFromEarlierAnalysis() {
        AnalysisId currentAnalysis = new AnalysisId("analysis-2");
        EvidenceId currentEvidence = new EvidenceId("evidence-2");
        repository.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), currentAnalysis,
                "基于上一轮证据继续分析", NOW.plusSeconds(1)));
        repository.createEvidenceRequest(new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST, currentEvidence, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), currentAnalysis,
                RUN_ID,
                List.of(COLLECTION_ID), List.of(), Set.of(EvidenceDimension.METHOD_PATH),
                512 * 1024, 1024 * 1024, NOW.plusSeconds(2)));
        MethodPathSummary reused = new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, currentEvidence, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID, RUN_ID,
                PLAN_ID, COLLECTION_ID, rawArtifact(),
                List.of(), List.of(), List.of(), false, NOW.plusSeconds(3));

        Path persisted = repository.createMethodPathSummary(reused);

        assertTrue(Files.isRegularFile(persisted));
    }

    private static EvidenceBuildRequest request() {
        return new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID,
                RUN_ID,
                List.of(COLLECTION_ID), List.of(), Set.of(EvidenceDimension.METHOD_PATH),
                512 * 1024, 1024 * 1024, NOW);
    }

    private static MethodPathSummary summary() {
        return new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID, RUN_ID,
                PLAN_ID, COLLECTION_ID, rawArtifact(),
                List.of(), List.of(), List.of(), false, NOW);
    }

    private static NormalizationManifest manifest(ArtifactReference summaryArtifact) {
        return new NormalizationManifest(
                SchemaVersions.NORMALIZATION_MANIFEST, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID, RUN_ID,
                PLAN_ID, COLLECTION_ID, "CODEPATH", "method-path-normalizer", "1.0",
                NormalizationStatus.COMPLETE, rawArtifact(), Optional.of(summaryArtifact),
                NormalizationBudget.defaults(), 1, 0, List.of(), Optional.empty(), "", NOW);
    }

    private static CollectionValidation validation() {
        return new CollectionValidation(
                SchemaVersions.COLLECTION_VALIDATION, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID, RUN_ID,
                PLAN_ID, COLLECTION_ID, "CODEPATH", EvidenceValidationStatus.VALID,
                List.of(), Set.of(EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION),
                Optional.of(summaryArtifact()), NOW);
    }

    private static EvidenceBundle bundle() {
        EvidenceFact fact = new EvidenceFact(
                ClaimClassification.CONFIRMED_FACT, EvidenceDimension.METHOD_PATH,
                "METHOD_PATH_PRESENT", "已生成方法路径摘要", List.of(summaryArtifact()),
                Optional.empty());
        return new EvidenceBundle(
                SchemaVersions.EVIDENCE_BUNDLE, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID,
                List.of(fact), List.of(),
                Set.of(EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION),
                List.of(summaryArtifact()), false, NOW);
    }

    private static SufficiencyEvaluation sufficiency() {
        return new SufficiencyEvaluation(
                SchemaVersions.SUFFICIENCY_EVALUATION, EVIDENCE_ID, CASE_ID,
                CaseArchiveRepositoryTest.context().contextId(), ANALYSIS_ID,
                SufficiencyStatus.SUFFICIENT,
                Set.of(EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION),
                Set.of(EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION),
                Set.of(), List.of(), NOW);
    }

    private static ArtifactReference rawArtifact() {
        return new ArtifactReference(
                "raw-1", "CODEPATH_FILTERED_TRACE",
                "collections/collection-1/raw/filtered.jsonl",
                "application/x-ndjson", HASH, 3);
    }

    private static ArtifactReference summaryArtifact() {
        return new ArtifactReference(
                "summary-1", "METHOD_PATH_SUMMARY",
                "collections/collection-1/derived/evidence-1/method-path-summary.json",
                "application/json", HASH, 100);
    }
}
