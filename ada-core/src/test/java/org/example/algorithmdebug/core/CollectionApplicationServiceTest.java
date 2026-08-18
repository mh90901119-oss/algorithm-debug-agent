package org.example.algorithmdebug.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.BuildTool;
import org.example.algorithmdebug.adapter.InputLocator;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultParser;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextRecord;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProcessOutcome;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.contracts.TestOutcome;
import org.example.algorithmdebug.contracts.SufficiencyEvaluation;
import org.example.algorithmdebug.contracts.SufficiencyStatus;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.MethodPathCollectionResult;
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.methodpath.MethodPathManifest;
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final PlanId PLAN_ID = new PlanId("plan-1");
    private static final TargetTest TARGET =
            new TargetTest("fixture.TargetTest", "caseUnderTest");

    @TempDir
    Path temporaryDirectory;

    private Path workspace;
    private Path module;
    private Path scheduleOutput;
    private ContextRecord context;
    private BoundedDocumentMapper mapper;
    private AtomicDocumentWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        module = Files.createDirectory(temporaryDirectory.resolve("module"));
        scheduleOutput = Files.createDirectories(module.resolve("target/schedules"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path targetSource = module.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(targetSource.getParent());
        Files.writeString(targetSource, """
                package fixture;
                class TargetTest { void caseUnderTest() { } }
                """);

        mapper = new BoundedDocumentMapper();
        writer = new AtomicDocumentWriter();
        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        Files.createDirectories(layout.projectCases(PROJECT_ID));
        context = new ContextRecord(SchemaVersions.CONTEXT_RECORD, CASE_ID, CONTEXT_ID, NOW);
        new ProjectRegistrationRepository(mapper, writer).create(layout, new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "fixture",
                portable(temporaryDirectory), portable(module), portable(module), "pom.xml", "MAVEN",
                "a".repeat(64), NOW));
        CaseArchiveRepository archive = archive();
        archive.createCase(new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET,
                "fixture", "why", NOW));
        archive.createContext(context);
        archive.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID, "continue", NOW));

        StaticAnalysisApplicationService staticAnalysis = new StaticAnalysisApplicationService(
                new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                new JavaSourceCallGraphAnalyzer(), new CodePathPlanCompiler(), fixedClock());
        staticAnalysis.analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        staticAnalysis.createCodePathPlan(
                workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID,
                new CodePathPlanRequest(
                        PLAN_ID, List.of("fixture.TargetTest#caseUnderTest()V"), "定位",
                        org.example.algorithmdebug.contracts.CollectionBudget.defaults(), NOW));
    }

    @Test
    void archivesRequestAgentFailureManifestAndBaselineWhenMavenIsUnavailable() {
        AtomicInteger collectorCalls = new AtomicInteger();
        CollectionApplicationService service = new CollectionApplicationService(
                new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                new AdapterCatalog(List.of(new StubAdapter())),
                new OpaqueIdGenerator(() -> "fixed"), fixedClock(), Optional.empty(),
                Path.of("java"), request -> {
                    collectorCalls.incrementAndGet();
                    throw new AssertionError("Collector must not start without Maven");
                }, (maven, root, output) -> {
                    throw new AssertionError("Classpath resolution must not start without Maven");
                });

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service.executeCodePath(workspace, PROJECT_ID, CASE_ID, PLAN_ID));

        assertEquals("MAVEN_NOT_FOUND", failure.code());
        assertEquals(0, collectorCalls.get());
        Path collection = WorkspaceLayout.of(workspace).projectCases(PROJECT_ID)
                .resolve("case-1/collections/collection-fixed");
        assertTrue(Files.isRegularFile(collection.resolve("collection-request.json")));
        assertTrue(Files.isRegularFile(collection.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(collection.resolve("validation/baseline-check.json")));
        MethodPathManifest manifest = mapper.readJson(
                collection.resolve("manifest.json"), MethodPathManifest.class);
        assertEquals(CollectionCompletion.AGENT_FAILED, manifest.completion());
        assertFalse(manifest.processStarted());
        assertEquals(-1, manifest.exitCode());
        assertEquals("MAVEN_NOT_FOUND", manifest.agentFailure().orElseThrow().code());
    }

    @Test
    void successfulCollectionWithMatchingBaselineReturnsOnlyExistingArtifactReferences()
            throws Exception {
        String gantt = "{\"schedule\":1}";
        establishBaseline(gantt);
        CollectionApplicationService service = service(
                collector(CollectionCompletion.SUCCESS, Optional.of(gantt)));

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.executeCodePath(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals("SUCCESS", result.summary().completion());
        assertEquals(ComparisonOutcome.MATCHED, result.summary().baselineOutcome());
        assertTrue(result.summary().evidenceUsable());
        assertEquals(
                result.artifacts().stream().map(
                        org.example.algorithmdebug.contracts.ArtifactReference::relativePath).toList(),
                result.summary().artifactRelativePaths());
        Path caseRoot = WorkspaceLayout.of(workspace).projectCases(PROJECT_ID).resolve(CASE_ID.value());
        assertTrue(result.artifacts().stream().allMatch(reference ->
                Files.isRegularFile(caseRoot.resolve(reference.relativePath()))));
        Set<String> types = result.artifacts().stream()
                .map(org.example.algorithmdebug.contracts.ArtifactReference::artifactType)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(types.containsAll(Set.of(
                "METHOD_PATH_SUMMARY", "NORMALIZATION_MANIFEST", "COLLECTION_VALIDATION",
                "EVIDENCE_BUILD_REQUEST", "EVIDENCE_BUNDLE", "SUFFICIENCY_EVALUATION")));
        SufficiencyEvaluation sufficiency = mapper.readJson(
                caseRoot.resolve("evidence/evidence-fixed/sufficiency-evaluation.json"),
                SufficiencyEvaluation.class);
        assertEquals(SufficiencyStatus.SUFFICIENT, sufficiency.status());
    }

    @Test
    void targetFailureWithGanttRemainsAnalyzableButCannotBecomeConfirmationEvidence()
            throws Exception {
        String gantt = "{\"schedule\":1}";
        establishBaseline(gantt);
        CollectionApplicationService service = service(
                collector(CollectionCompletion.TARGET_FAILED, Optional.of(gantt)));

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.executeCodePath(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals("TARGET_FAILED", result.summary().completion());
        assertEquals(ComparisonOutcome.INCOMPARABLE, result.summary().baselineOutcome());
        assertFalse(result.summary().evidenceUsable());
        assertTrue(result.artifacts().stream().anyMatch(reference ->
                "GANTT_RAW".equals(reference.artifactType())));
    }

    @Test
    void targetFailureWithoutGanttMatchesFailureBaselineAndKeepsPreFailureTraceUsable()
            throws Exception {
        establishFailureBaseline("No solution after maximum iterations");
        writeFailureReport("No solution after maximum iterations");
        CollectionApplicationService service = service(
                collector(CollectionCompletion.TARGET_FAILED, Optional.empty()));

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.executeCodePath(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals(ComparisonOutcome.MATCHED, result.summary().baselineOutcome());
        assertTrue(result.summary().evidenceUsable());
        var baseline = mapper.readJson(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID)
                        .resolve("case-1/collections/collection-fixed/validation/baseline-check.json"),
                org.example.algorithmdebug.contracts.CollectionBaselineCheck.class);
        assertTrue(baseline.currentGanttSha256().isEmpty());
    }

    @Test
    void changedTargetFailureFingerprintIsRejected() throws Exception {
        establishFailureBaseline("No solution after maximum iterations");
        writeFailureReport("Input file was not found");
        CollectionApplicationService service = service(
                collector(CollectionCompletion.TARGET_FAILED, Optional.empty()));

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.executeCodePath(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals(ComparisonOutcome.CHANGED, result.summary().baselineOutcome());
        assertFalse(result.summary().evidenceUsable());
    }

    @Test
    void changedGanttIsArchivedButRejectedByBaselineGate() throws Exception {
        establishBaseline("{\"schedule\":1}");
        CollectionApplicationService service = service(
                collector(CollectionCompletion.SUCCESS, Optional.of("{\"schedule\":2}")));

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.executeCodePath(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals(ComparisonOutcome.CHANGED, result.summary().baselineOutcome());
        assertFalse(result.summary().evidenceUsable());
    }

    @Test
    void zeroHitCollectionStillProducesInconclusiveEvidence() throws Exception {
        establishBaseline("{\"schedule\":1}");
        MultiArtifactBackedResult<CollectionExecutionSummary> zeroHit = service(collector(
                CollectionCompletion.SUCCESS, Optional.of("{\"schedule\":1}"), "", 0,
                List.of())).executeCodePath(workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertFalse(zeroHit.summary().evidenceUsable());
        assertTrue(zeroHit.artifacts().stream().anyMatch(reference ->
                "EVIDENCE_BUNDLE".equals(reference.artifactType())));
        assertTrue(zeroHit.artifacts().stream().noneMatch(reference ->
                "POST_PROCESSING_FAILURE".equals(reference.artifactType())));
        assertEquals(SufficiencyStatus.INSUFFICIENT, mapper.readJson(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID).resolve(
                        "case-1/evidence/evidence-fixed/sufficiency-evaluation.json"),
                SufficiencyEvaluation.class).status());

    }

    @Test
    void truncatedCollectionStillProducesInconclusiveEvidence() throws Exception {
        establishBaseline("{\"schedule\":1}");
        MultiArtifactBackedResult<CollectionExecutionSummary> result = service(collector(
                CollectionCompletion.TRUNCATED, Optional.of("{\"schedule\":1}")))
                .executeCodePath(workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertFalse(result.summary().evidenceUsable());
        assertTrue(result.artifacts().stream().anyMatch(reference ->
                "EVIDENCE_BUNDLE".equals(reference.artifactType())));
        assertTrue(result.artifacts().stream().noneMatch(reference ->
                "POST_PROCESSING_FAILURE".equals(reference.artifactType())));
        assertEquals(SufficiencyStatus.INSUFFICIENT, mapper.readJson(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID).resolve(
                        "case-1/evidence/evidence-fixed/sufficiency-evaluation.json"),
                SufficiencyEvaluation.class).status());
    }

    @Test
    void malformedRawKeepsCollectorArtifactsAndExposesSeparatePostProcessingFailure()
            throws Exception {
        establishBaseline("{\"schedule\":1}");
        MethodPathCollector malformed = request -> {
            MethodPathCollectionResult collected = collector(
                    CollectionCompletion.SUCCESS, Optional.of("{\"schedule\":1}")).collect(request);
            try {
                Files.writeString(collected.rawTrace(), "{}\n");
                return collected;
            } catch (java.io.IOException failure) {
                throw new MethodPathCollectionException("TEST_IO", "fixture write failed", failure);
            }
        };

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service(malformed)
                .executeCodePath(workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertFalse(result.summary().evidenceUsable());
        assertTrue(result.artifacts().stream().anyMatch(reference ->
                "CODEPATH_RAW".equals(reference.artifactType())));
        assertTrue(result.artifacts().stream().anyMatch(reference ->
                "CODEPATH_MANIFEST".equals(reference.artifactType())));
        assertTrue(result.artifacts().stream().anyMatch(reference ->
                "POST_PROCESSING_FAILURE".equals(reference.artifactType())));
        Path collectionRoot = WorkspaceLayout.of(workspace).projectCases(PROJECT_ID).resolve(
                "case-1/collections/collection-fixed");
        AgentFailureDiagnostic diagnostic = mapper.readJson(
                collectionRoot.resolve("validation/post-processing-failure.json"),
                AgentFailureDiagnostic.class);
        assertEquals("COLLECTION_POST_PROCESSING_FAILED", diagnostic.code());
        assertEquals(CollectionCompletion.SUCCESS, mapper.readJson(
                collectionRoot.resolve("manifest.json"), MethodPathManifest.class).completion());
    }

    private CollectionApplicationService service(MethodPathCollector collector) {
        return new CollectionApplicationService(
                new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                new AdapterCatalog(List.of(new StubAdapter())),
                new OpaqueIdGenerator(() -> "fixed"), fixedClock(), Optional.of(Path.of("mvn")),
                Path.of("java"), collector, (maven, root, output) -> List.of("classes"));
    }

    private MethodPathCollector collector(
            CollectionCompletion completion, Optional<String> ganttJson) {
        return collector(completion, ganttJson, """
                {"eventId":1,"eventType":"METHOD_ENTER","depth":0,"className":"fixture.TargetTest","methodName":"caseUnderTest","descriptor":"()V"}
                {"eventId":2,"eventType":"METHOD_EXIT","depth":0,"className":"fixture.TargetTest","methodName":"caseUnderTest","descriptor":"()V"}
                """, 2, completion == CollectionCompletion.TRUNCATED
                ? List.of("EVENT_BUDGET_EXCEEDED") : List.of());
    }

    private MethodPathCollector collector(
            CollectionCompletion completion,
            Optional<String> ganttJson,
            String rawJsonl,
            long eventCount,
            List<String> truncationReasons) {
        return request -> {
            try {
                Path raw = request.collectionDirectory().resolve("raw/codepath.jsonl");
                Path stdout = request.collectionDirectory().resolve("logs/stdout.log");
                Path stderr = request.collectionDirectory().resolve("logs/stderr.log");
                Files.createDirectories(raw.getParent());
                Files.createDirectories(stdout.getParent());
                Files.writeString(raw, rawJsonl);
                Files.writeString(stdout, "collector summary\n");
                Files.writeString(stderr, completion == CollectionCompletion.TARGET_FAILED
                        ? "java.lang.AssertionError: expected schedule\n" : "");
                if (ganttJson.isPresent()) {
                    Files.writeString(scheduleOutput.resolve("result.json"), ganttJson.orElseThrow());
                }
                MethodPathManifest manifest = new MethodPathManifest(
                        "2.0", request.caseId(), request.contextId(), request.analysisId(),
                        request.runId(), request.plan().planId(), request.collectionId(),
                        "code-path-tracer", "0.1.0", Optional.of("a".repeat(64)),
                        sha(mapper.writeJson(request.plan())), completion, "COMPLETE", true,
                        completion == CollectionCompletion.TARGET_FAILED ? 2 : 0, false,
                        completion == CollectionCompletion.TARGET_FAILED ? "FAILED" : "PASSED",
                        1, completion == CollectionCompletion.TARGET_FAILED ? 0 : 1, 0,
                        completion == CollectionCompletion.TARGET_FAILED ? 1 : 0,
                        eventCount, Files.size(raw),
                        Optional.of(sha(Files.readAllBytes(raw))),
                        truncationReasons, Optional.empty(), "raw/codepath.jsonl",
                        "logs/stdout.log", "logs/stderr.log", NOW, NOW);
                return new MethodPathCollectionResult(
                        request, manifest, raw, stdout, stderr);
            } catch (java.io.IOException failure) {
                throw new MethodPathCollectionException(
                        "TEST_COLLECTOR_IO", "测试 Collector 无法写入产物", failure);
            }
        };
    }

    private void establishBaseline(String ganttJson) throws Exception {
        Path reference = Files.writeString(
                temporaryDirectory.resolve("baseline-gantt.json"), ganttJson);
        RunId baselineRun = new RunId("baseline-run");
        CaseArchiveRepository archive = archive();
        archive.startRun(new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                baselineRun, TARGET, "UNINSTRUMENTED", NOW));
        archive.completeRun(successfulBaselineOutcome(baselineRun));
        RunResultFingerprint fingerprint = new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, CONTEXT_ID, baselineRun,
                Optional.of(sha(Files.readAllBytes(reference))),
                Optional.of(new org.example.algorithmdebug.harness.JsonTokenContentHasher()
                        .sha256(reference)), Optional.empty());
        archive.createRunResultFingerprint(fingerprint);
        archive.createReproductionIfAbsent(fingerprint);
    }

    private void establishFailureBaseline(String message) throws Exception {
        RunId baselineRun = new RunId("baseline-run");
        CaseArchiveRepository archive = archive();
        archive.startRun(new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                baselineRun, TARGET, "UNINSTRUMENTED", NOW));
        archive.completeRun(new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, baselineRun, ProcessOutcome.FAILED,
                TestOutcome.ERROR, GanttOutcome.ABSENT,
                Optional.of(new org.example.algorithmdebug.contracts.TargetFailureDiagnostic(
                        org.example.algorithmdebug.contracts.FailureCategory.TEST_ERROR,
                        "java.lang.IllegalStateException", message, "",
                        "fixture.Algorithm.solve(Algorithm.java:42)")),
                Optional.empty(), ComparisonOutcome.NOT_COMPARED, "not compared", List.of()));
        var diagnostic = new org.example.algorithmdebug.contracts.TargetFailureDiagnostic(
                org.example.algorithmdebug.contracts.FailureCategory.TEST_ERROR,
                "java.lang.IllegalStateException", message, "",
                "fixture.Algorithm.solve(Algorithm.java:42)");
        RunResultFingerprint fingerprint = new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, CONTEXT_ID, baselineRun,
                Optional.empty(), Optional.empty(), Optional.of(
                        new org.example.algorithmdebug.harness.TargetFailureFingerprinter()
                                .sha256(diagnostic)));
        archive.createRunResultFingerprint(fingerprint);
        archive.createReproductionIfAbsent(fingerprint);
    }

    private void writeFailureReport(String message) throws Exception {
        Path reports = Files.createDirectories(module.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-fixture.TargetTest.xml"), """
                <testsuite>
                  <testcase classname="fixture.TargetTest" name="caseUnderTest">
                    <error type="java.lang.IllegalStateException" message="%s"><![CDATA[
                java.lang.IllegalStateException: %s
                    at fixture.Algorithm.solve(Algorithm.java:42)
                    ]]></error>
                  </testcase>
                </testsuite>
                """.formatted(message, message));
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RunOutcomeSummary successfulBaselineOutcome(RunId runId) {
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, runId, ProcessOutcome.SUCCEEDED,
                TestOutcome.PASSED, GanttOutcome.ABSENT, Optional.empty(), Optional.empty(),
                ComparisonOutcome.NOT_COMPARED, "not compared", List.of());
    }

    private CaseArchiveRepository archive() {
        return new CaseArchiveRepository(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID), mapper, writer);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static String portable(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private record Snapshot(String schemaVersion, String value) implements ScheduleResultSnapshot {
    }

    private final class StubAdapter implements TargetProjectAdapter<Snapshot> {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(
                    "fixture", "1.0", "fixture", Set.of(
                    AdapterCapability.BASELINE_EXECUTION,
                    AdapterCapability.INPUT_LOCATION,
                    AdapterCapability.SCHEDULE_RESULT));
        }

        @Override
        public ProjectDescriptor inspect(Path root) {
            return new ProjectDescriptor(
                    PROJECT_ID, "fixture", root.toAbsolutePath(), BuildTool.MAVEN, Path.of("pom.xml"));
        }

        @Override
        public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest targetTest, RunMode runMode) {
            return new TestLaunchSpec(
                    project, targetTest, runMode, List.of("test"),
                    Map.of("test", targetTest.selector()), List.of(), Duration.ofSeconds(10));
        }

        @Override
        public InputLocator inputLocator() {
            return (project, targetTest) -> Optional.empty();
        }

        @Override
        public ScheduleResultSource scheduleResultSource(
                ProjectDescriptor project, TargetTest targetTest) {
            return new ScheduleResultSource(scheduleOutput, false);
        }

        @Override
        public ScheduleResultParser<Snapshot> scheduleResultParser() {
            return path -> new Snapshot("1.0", "unused");
        }
    }
}
