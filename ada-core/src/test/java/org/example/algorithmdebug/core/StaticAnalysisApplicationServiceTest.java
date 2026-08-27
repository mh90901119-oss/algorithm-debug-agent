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
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextRecord;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.plan.JdwpPlanRequest;
import org.example.algorithmdebug.plan.JdwpTracepointRequest;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.example.algorithmdebug.staticanalysis.JavaTestAlgorithmInputLocator;
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
    private ContextRecord context;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        module = Files.createDirectory(temporaryDirectory.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path targetSource = module.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(targetSource.getParent());
        Files.writeString(targetSource, """
                package fixture;
                class TargetTest { void caseUnderTest() { String algorithmInput = "input/caseinput.json"; } }
                """);
        Path input = module.resolve("input/caseinput.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{}");

        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        Files.createDirectories(layout.projectCases(PROJECT_ID));
        BoundedDocumentMapper mapper = new BoundedDocumentMapper();
        AtomicDocumentWriter writer = new AtomicDocumentWriter();
        context = new ContextRecord(SchemaVersions.CONTEXT_RECORD, CASE_ID, CONTEXT_ID, NOW);
        new ProjectRegistrationRepository(mapper, writer).create(layout, new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "fixture",
                portable(temporaryDirectory), portable(module), portable(module), "pom.xml", "MAVEN",
                "a".repeat(64), NOW));
        CaseArchiveRepository archive = new CaseArchiveRepository(
                layout.projectCases(PROJECT_ID), mapper, writer);
        archive.createCase(new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET,
                "fixture", "why", NOW));
        archive.createContext(context);
        archive.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID, "continue", NOW));
        new AlgorithmInputApplicationService(
                new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                new JavaTestAlgorithmInputLocator(), Clock.fixed(NOW, ZoneOffset.UTC))
                .capture(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
    }

    @Test
    void catalogsMethodsWithoutComputingWholeModuleFingerprint() {
        ArtifactBackedResult<StaticAnalysisSummary> result =
                service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        MethodCatalog catalog = archive().requireMethodCatalog(CASE_ID, ANALYSIS_ID);

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
                        org.example.algorithmdebug.contracts.CollectionBudget.defaults(), NOW));

        assertEquals(1, result.summary().selectorCount());
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
                org.example.algorithmdebug.contracts.CollectionBudget.defaults(), NOW);

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service().createCodePathPlan(
                        workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID, request));

        assertEquals("PLAN_COMPILATION_FAILED", failure.code());
    }

    @Test
    void reportsMissingTargetTestWithoutGenericStaticFailure() throws Exception {
        Path targetSource = module.resolve("src/test/java/fixture/TargetTest.java");
        Files.writeString(targetSource, """
                package fixture;
                class TargetTest { void anotherCase() { } }
                """);

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID));

        assertEquals("TARGET_TEST_NOT_FOUND", failure.code());
    }

    @Test
    void jdwpPlanReturnsBoundedSummaryAndCaseRelativeArtifact() {
        service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        String methodKey = archive().requireMethodCatalog(CASE_ID, ANALYSIS_ID)
                .entries().getFirst().methodKey();

        ArtifactBackedResult<JdwpPlanSummary> result = service().createJdwpPlan(
                workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID,
                new JdwpPlanRequest(
                        new PlanId("jdwp-plan-1"),
                        List.of(new JdwpTracepointRequest(
                                "target-entry", methodKey, 2, 3,
                                org.example.algorithmdebug.contracts.JdwpCaptureSpec.stackOnly())),
                        org.example.algorithmdebug.contracts.JdwpCollectionBudget.defaults(),
                        "查看目标方法调用栈", NOW));

        assertEquals(1, result.summary().tracepointCount());
        assertEquals("analyses/analysis-1/plans/jdwp-plan-1.json",
                result.artifact().relativePath());
        assertEquals(result.summary().planId(), archive().requireJdwpPlan(
                CASE_ID, ANALYSIS_ID, result.summary().planId()).planId());
    }

    @Test
    void mapsInvalidRationaleToPlanCompilationFailure() {
        service().analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        CodePathPlanRequest request = new CodePathPlanRequest(
                new PlanId("plan-rationale"),
                List.of("fixture.TargetTest#caseUnderTest()V"), "x".repeat(4_097),
                org.example.algorithmdebug.contracts.CollectionBudget.defaults(), NOW);

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
                org.example.algorithmdebug.contracts.CollectionBudget.defaults(), NOW);
        service.createCodePathPlan(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID, request);
        CaseRunException planFailure = assertThrows(CaseRunException.class, () ->
                service.createCodePathPlan(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID, request));

        assertEquals("STATIC_ARCHIVE_FAILED", staticFailure.code());
        assertEquals("PLAN_ARCHIVE_FAILED", planFailure.code());
    }

    private StaticAnalysisApplicationService service() {
        return new StaticAnalysisApplicationService(
                new ProjectRegistrationRepository(
                        new BoundedDocumentMapper(), new AtomicDocumentWriter()),
                new BoundedDocumentMapper(), new AtomicDocumentWriter(),
                new JavaSourceCallGraphAnalyzer(), new CodePathPlanCompiler(),
                Clock.fixed(NOW, ZoneOffset.UTC));
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
