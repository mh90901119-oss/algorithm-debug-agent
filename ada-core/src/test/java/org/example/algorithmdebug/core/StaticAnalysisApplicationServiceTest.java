package org.example.algorithmdebug.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.ContextInputProbe;
import org.example.algorithmdebug.casecore.ContextSnapshotBuilder;
import org.example.algorithmdebug.casecore.ContextSnapshotRequest;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceSnapshot;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAnalysisApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "caseUnderTest");

    @TempDir
    Path temporaryDirectory;

    private Path workspace;
    private Path module;
    private Path targetSource;
    private ContextSnapshot context;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        module = Files.createDirectory(temporaryDirectory.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        targetSource = module.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(targetSource.getParent());
        Files.writeString(targetSource, """
                package fixture;
                class TargetTest { void caseUnderTest() { } }
                """);

        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        Files.createDirectories(layout.projectCases(PROJECT_ID));
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        ContextSnapshotBuilder snapshots = new ContextSnapshotBuilder();
        context = snapshots.build(new ContextSnapshotRequest(
                CASE_ID, CONTEXT_ID, PROJECT_ID, TARGET, module, temporaryDirectory,
                "UNAVAILABLE", "21", "fixture", "1.0",
                ContextInputProbe.missing("input/case.json", "not required"), NOW));
        new ProjectRegistrationRepository(mapper, writer).create(layout, new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "fixture",
                portable(temporaryDirectory), portable(module), portable(module), "pom.xml", "MAVEN",
                context.buildSnapshot().pomSha256(), NOW));
        CaseArchiveRepository archive = new CaseArchiveRepository(
                layout.projectCases(PROJECT_ID), mapper, writer);
        archive.createCase(new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET, "why", NOW));
        archive.createContext(context);
        archive.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID, "continue", NOW));
    }

    @Test
    void catalogsUseContextSourceSnapshotRatherThanWholeContextFingerprint() {
        ArtifactBackedResult<StaticAnalysisSummary> result =
                service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        MethodCatalog catalog = archive().requireMethodCatalog(CASE_ID, ANALYSIS_ID);

        assertEquals(context.sourceSnapshot().sha256(), catalog.sourceFingerprintSha256());
        assertEquals(1, result.summary().methodCount());
        assertEquals("analyses/analysis-1/method-catalog.json",
                result.artifact().relativePath());
        assertEquals("application/json", result.artifact().mediaType());
        assertEquals(Files.exists(module.resolve(result.artifact().relativePath())), false);
    }

    @Test
    void codePathPlanReturnsBoundedSummaryAndCaseRelativeArtifact() throws Exception {
        service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);

        ArtifactBackedResult<CodePathPlanSummary> result = service().createCodePathPlan(
                workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID,
                new CodePathPlanRequest(
                        new PlanId("plan-1"),
                        List.of("fixture.TargetTest#caseUnderTest()V"), "定位",
                        org.example.algorithmdebug.contracts.CollectionBudget.defaults(),
                        0, NOW));

        assertEquals(1, result.summary().selectorCount());
        assertEquals(20_000, result.summary().estimatedPackageEvents());
        assertEquals("analyses/analysis-1/plans/plan-1.json",
                result.artifact().relativePath());
        assertEquals(result.artifact().sizeBytes(), Files.size(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID)
                        .resolve(CASE_ID.value()).resolve(result.artifact().relativePath())));
    }

    @Test
    void mapsRejectedCodePathSelectionToPlanStageFailure() {
        service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("plan-1"), List.of("fixture.Missing#run()V"), "定位",
                org.example.algorithmdebug.contracts.CollectionBudget.defaults(), 0, NOW);

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service().createCodePathPlan(
                        workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID, request));

        assertEquals("PLAN_COMPILATION_FAILED", failure.code());
    }

    @Test
    void mapsInvalidRationaleToPlanCompilationFailure() {
        service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("plan-rationale"),
                List.of("fixture.TargetTest#caseUnderTest()V"), "x".repeat(4_097),
                org.example.algorithmdebug.contracts.CollectionBudget.defaults(), 0, NOW);

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service().createCodePathPlan(
                        workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID, request));

        assertEquals("PLAN_COMPILATION_FAILED", failure.code());
    }

    @Test
    void mapsStaticAndPlanArchiveFailuresToStageCodes() {
        StaticAnalysisApplicationService service = service();
        service.analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);

        CaseRunException staticFailure = assertThrows(CaseRunException.class, () ->
                service.analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID));

        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("plan-archive"), List.of("fixture.TargetTest#caseUnderTest()V"), "定位",
                org.example.algorithmdebug.contracts.CollectionBudget.defaults(), 0, NOW);
        service.createCodePathPlan(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID, request);
        CaseRunException planFailure = assertThrows(CaseRunException.class, () ->
                service.createCodePathPlan(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID, request));

        assertEquals("STATIC_ARCHIVE_FAILED", staticFailure.code());
        assertEquals("PLAN_ARCHIVE_FAILED", planFailure.code());
    }

    @Test
    void rejectsSourceDriftBeforeStaticAnalysis() throws Exception {
        Files.writeString(targetSource, """
                package fixture;
                class TargetTest { void caseUnderTest() { int changed = 1; } }
                """);

        CaseRunException failure = assertThrows(CaseRunException.class,
                () -> service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID));

        assertEquals("STATIC_SOURCE_DRIFT", failure.code());
    }

    @Test
    void rejectsSourceDriftObservedAfterStaticAnalysis() {
        SourceSnapshot changed = new SourceSnapshot(
                "f".repeat(64), context.sourceSnapshot().fileCount(),
                context.sourceSnapshot().totalBytes(), context.sourceSnapshot().completeness());
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        SourceSnapshotReader reader = ignored -> reads.getAndIncrement() == 0
                ? context.sourceSnapshot() : changed;

        CaseRunException failure = assertThrows(CaseRunException.class,
                () -> service(reader).analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID));

        assertEquals("STATIC_SOURCE_DRIFT", failure.code());
        assertEquals(2, reads.get());
    }

    private StaticAnalysisApplicationService service() {
        return service(new ContextSnapshotBuilder()::captureSourceSnapshot);
    }

    private StaticAnalysisApplicationService service(SourceSnapshotReader sourceSnapshots) {
        return new StaticAnalysisApplicationService(
                new ProjectRegistrationRepository(
                        new BoundedDocumentMapper(), new AtomicDocumentWriter()),
                new BoundedDocumentMapper(), new AtomicDocumentWriter(),
                new JavaSourceCallGraphAnalyzer(), new CodePathPlanCompiler(),
                Clock.fixed(NOW, ZoneOffset.UTC), sourceSnapshots);
    }

    private CaseArchiveRepository archive() {
        return new CaseArchiveRepository(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID),
                new BoundedDocumentMapper(), new AtomicDocumentWriter());
    }

    private static String portable(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
