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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import org.example.algorithmdebug.adapter.AdapterCapability;
import org.example.algorithmdebug.adapter.AdapterDescriptor;
import org.example.algorithmdebug.adapter.BuildTool;
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
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextRecord;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.contracts.JdwpCollectionManifest;
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
import org.example.algorithmdebug.harness.RunCompletion;
import org.example.algorithmdebug.harness.RunLog;
import org.example.algorithmdebug.harness.RunResult;
import org.example.algorithmdebug.harness.TerminationReport;
import org.example.algorithmdebug.jdwp.JdwpExecutionResult;
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.plan.JdwpPlanRequest;
import org.example.algorithmdebug.plan.JdwpTracepointRequest;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.example.algorithmdebug.staticanalysis.JavaTestAlgorithmInputLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdwpCollectionApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final PlanId PLAN_ID = new PlanId("jdwp-plan-1");
    private static final TargetTest TARGET = new TargetTest("fixture.TargetTest", "caseUnderTest");

    @TempDir Path temporaryDirectory;
    private Path workspace;
    private Path module;
    private Path scheduleOutput;
    private Path maven;
    private Path java;
    private Path collectorJar;
    private ContextRecord context;
    private BoundedDocumentMapper mapper;
    private AtomicDocumentWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        module = Files.createDirectory(temporaryDirectory.resolve("module"));
        scheduleOutput = Files.createDirectories(module.resolve("target/schedules"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path source = module.resolve("src/test/java/fixture/TargetTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;
                class TargetTest {
                    void caseUnderTest() { String algorithmInput = "input/caseinput.json"; int marker = 1; }
                }
                """);
        Path input = module.resolve("input/caseinput.json");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "{}");
        maven = Files.writeString(temporaryDirectory.resolve("mvn.cmd"), "stub").toAbsolutePath();
        java = Files.writeString(temporaryDirectory.resolve("java.exe"), "stub").toAbsolutePath();
        collectorJar = Files.writeString(temporaryDirectory.resolve("collector.jar"), "collector")
                .toAbsolutePath();
        mapper = new BoundedDocumentMapper();
        writer = new AtomicDocumentWriter();
        WorkspaceLayout layout = WorkspaceLayout.of(workspace);
        Files.createDirectories(layout.projectCases(PROJECT_ID));
        context = new ContextRecord(SchemaVersions.CONTEXT_RECORD, CASE_ID, CONTEXT_ID, NOW);
        new ProjectRegistrationRepository(mapper, writer).create(layout, new ProjectRegistration(
                SchemaVersions.PROJECT_REGISTRATION, PROJECT_ID, "fixture",
                portable(temporaryDirectory), portable(module), portable(module), "pom.xml", "MAVEN",
                "target/schedules", NOW));
        CaseArchiveRepository archive = archive();
        archive.createCase(new CaseManifest(
                SchemaVersions.CASE_MANIFEST, CASE_ID, PROJECT_ID, TARGET,
                "fixture", "why", NOW));
        archive.createContext(context);
        archive.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID, "continue", NOW));
        new AlgorithmInputApplicationService(
                new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                new JavaTestAlgorithmInputLocator(), fixedClock())
                .capture(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        StaticAnalysisApplicationService staticAnalysis = new StaticAnalysisApplicationService(
                new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                new JavaSourceCallGraphAnalyzer(), new CodePathPlanCompiler(), fixedClock());
        staticAnalysis.analyze(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID);
        var catalog = archive.requireMethodCatalog(CASE_ID, ANALYSIS_ID);
        var anchor = catalog.entries().getFirst().sourceAnchor();
        staticAnalysis.createJdwpPlan(workspace, PROJECT_ID, CASE_ID, ANALYSIS_ID,
                new JdwpPlanRequest(PLAN_ID, List.of(new JdwpTracepointRequest(
                        "target-entry", catalog.entries().getFirst().methodKey(),
                        anchor.startLine(), 3, JdwpCaptureSpec.stackOnly())),
                        JdwpCollectionBudget.defaults(), "查看调用栈", NOW));
    }

    @Test
    void successfulCollectionArchivesAllPortableArtifactsWithoutGanttBaseline() throws Exception {
        establishBaseline("{\"schedule\":1}");
        JdwpCollectionApplicationService service = service(
                request -> successfulExecution(request, "{\"schedule\":1}", 101, 102));

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.execute(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals("SUCCESS", result.summary().completion());
        assertEquals(ComparisonOutcome.NOT_COMPARED, result.summary().baselineOutcome());
        assertTrue(result.summary().evidenceUsable());
        Set<String> types = result.artifacts().stream()
                .map(reference -> reference.artifactType()).collect(Collectors.toSet());
        assertTrue(types.containsAll(Set.of(
                "COLLECTION_REQUEST", "JDWP_COLLECTOR_PLAN", "JDWP_RAW",
                "JDWP_EXTERNAL_MANIFEST", "JDWP_MANIFEST",
                "COLLECTION_BASELINE", "TARGET_STDOUT", "TARGET_STDERR",
                "COLLECTOR_STDOUT", "COLLECTOR_STDERR", "JDWP_SNAPSHOT_SUMMARY",
                "NORMALIZATION_MANIFEST", "COLLECTION_VALIDATION",
                "EVIDENCE_BUILD_REQUEST", "EVIDENCE_BUNDLE", "SUFFICIENCY_EVALUATION")));
        assertTrue(!types.contains("JDWP_PLAN"));
        assertFalse(types.contains("GANTT_RAW"));
        assertTrue(result.artifacts().stream().noneMatch(reference ->
                Path.of(reference.relativePath()).isAbsolute()));
        assertEquals(SufficiencyStatus.SUFFICIENT, mapper.readJson(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID).resolve(
                        "case-1/evidence/evidence-fixed/sufficiency-evaluation.json"),
                SufficiencyEvaluation.class).status());
    }

    @Test
    void truncatedCollectionStillProducesInconclusiveEvidence() throws Exception {
        establishBaseline("{\"schedule\":1}");
        JdwpCollectionApplicationService service = service(request -> {
            try {
                writeExternalArtifacts(request, "{\"schedule\":1}");
                return new JdwpExecutionResult(
                        request.port(), JdwpCollectionCompletion.TRUNCATED, true, true,
                        Optional.of(successfulRun(request.targetOptions().stdoutLog(),
                                request.targetOptions().stderrLog(), 121)),
                        Optional.of(successfulRun(request.collectorStdoutLog(),
                                request.collectorStderrLog(), 122)));
            } catch (java.io.IOException failure) {
                throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                        "TEST_IO", "fixture write failed", failure);
            }
        });

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.execute(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

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
    void changedGanttDoesNotBlockCurrentRunEvidence() throws Exception {
        establishBaseline("{\"schedule\":1}");
        JdwpCollectionApplicationService service = service(
                request -> successfulExecution(request, "{\"schedule\":2}", 103, 104));

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.execute(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals(ComparisonOutcome.NOT_COMPARED, result.summary().baselineOutcome());
        assertTrue(result.summary().evidenceUsable());
    }

    @Test
    void targetAssertionFailureWithGanttIsArchivedForAnalysisButNotConfirmation() throws Exception {
        establishBaseline("{\"schedule\":1}");
        JdwpCollectionApplicationService service = service(request -> {
            try {
                writeExternalArtifacts(request, "{\"schedule\":1}");
                return new JdwpExecutionResult(
                        request.port(), JdwpCollectionCompletion.TARGET_FAILED, true, true,
                        Optional.of(failedRun(request.targetOptions().stdoutLog(),
                                request.targetOptions().stderrLog(), 105)),
                        Optional.of(successfulRun(request.collectorStdoutLog(),
                                request.collectorStderrLog(), 106)));
            } catch (java.io.IOException failure) {
                throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                        "TEST_IO", "Failed to write the test artifact", failure);
            }
        });

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.execute(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals("TARGET_FAILED", result.summary().completion());
        assertEquals(ComparisonOutcome.INCOMPARABLE, result.summary().baselineOutcome());
        assertFalse(result.summary().evidenceUsable());
        assertFalse(result.artifacts().stream().anyMatch(reference ->
                "GANTT_RAW".equals(reference.artifactType())));
    }

    @Test
    void targetFailureWithoutGanttMatchesFailureBaselineAndKeepsPreFailureSnapshotsUsable()
            throws Exception {
        establishFailureBaseline("No solution after maximum iterations");
        writeFailureReport("No solution after maximum iterations");
        JdwpCollectionApplicationService service = service(request -> {
            try {
                writeExternalArtifacts(request, "{\"temporary\":true}");
                Files.deleteIfExists(scheduleOutput.resolve("result.json"));
                return new JdwpExecutionResult(
                        request.port(), JdwpCollectionCompletion.TARGET_FAILED, true, true,
                        Optional.of(failedRun(request.targetOptions().stdoutLog(),
                                request.targetOptions().stderrLog(), 111)),
                        Optional.of(successfulRun(request.collectorStdoutLog(),
                                request.collectorStderrLog(), 112)));
            } catch (java.io.IOException failure) {
                throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                        "TEST_IO", "测试产物写入失败", failure);
            }
        });

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.execute(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals(ComparisonOutcome.MATCHED, result.summary().baselineOutcome());
        assertTrue(result.summary().evidenceUsable());
    }

    @Test
    void attachFailurePreservesStructuredCauseAndFailureArtifacts() {
        JdwpCollectionApplicationService service = service(request -> {
            throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                    "JDWP_ATTACH_FAILED", "attach failed", new IllegalStateException("refused"),
                    true, true);
        });

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service.execute(workspace, PROJECT_ID, CASE_ID, PLAN_ID));

        assertEquals("JDWP_ATTACH_FAILED", failure.code());
        assertTrue(failure.getCause() instanceof org.example.algorithmdebug.jdwp.JdwpAdapterException);
        Path root = WorkspaceLayout.of(workspace).projectCases(PROJECT_ID)
                .resolve("case-1/collections/collection-fixed");
        JdwpCollectionManifest manifest = mapper.readJson(
                root.resolve("manifest.json"), JdwpCollectionManifest.class);
        assertEquals(JdwpCollectionCompletion.TOOL_FAILED, manifest.completion());
        assertEquals("JDWP_ATTACH_FAILED", manifest.agentFailure().orElseThrow().code());
        assertTrue(Files.isRegularFile(root.resolve("collector-plan.json")));
        assertTrue(Files.isRegularFile(root.resolve("validation/baseline-check.json")));
    }

    @Test
    void collectorProcessFailureWithoutRawOutputStillProducesBoundedSummary() throws Exception {
        JdwpCollectionApplicationService service = service(request -> {
            try {
                Files.writeString(request.targetOptions().stdoutLog(), "target ready\n");
                Files.writeString(request.targetOptions().stderrLog(), "");
                Files.writeString(request.collectorStdoutLog(), "");
                Files.writeString(request.collectorStderrLog(), "attach refused\n");
                return new JdwpExecutionResult(
                        request.port(), JdwpCollectionCompletion.TOOL_FAILED, true, true,
                        Optional.of(failedRun(request.targetOptions().stdoutLog(),
                                request.targetOptions().stderrLog(), 107)),
                        Optional.of(failedRun(request.collectorStdoutLog(),
                                request.collectorStderrLog(), 108)));
            } catch (java.io.IOException failure) {
                throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                        "TEST_IO", "测试日志写入失败", failure);
            }
        });

        MultiArtifactBackedResult<CollectionExecutionSummary> result = service.execute(
                workspace, PROJECT_ID, CASE_ID, PLAN_ID);

        assertEquals("TOOL_FAILED", result.summary().completion());
        assertEquals(ComparisonOutcome.INCOMPARABLE, result.summary().baselineOutcome());
        assertFalse(result.summary().evidenceUsable());
        assertTrue(result.artifacts().stream().anyMatch(reference ->
                "JDWP_MANIFEST".equals(reference.artifactType())));
        assertTrue(result.artifacts().stream().noneMatch(reference ->
                "JDWP_RAW".equals(reference.artifactType())));
    }

    @Test
    void invalidExternalManifestPreservesObservedProcessFacts() throws Exception {
        JdwpCollectionApplicationService service = service(request -> {
            try {
                writeExternalArtifacts(request, "{\"schedule\":1}");
                Path external = request.collectorOutputDirectory().resolve("collection-manifest.json");
                Files.writeString(external, Files.readString(external)
                        .replace("\"port\":51234", "\"port\":60000"));
                return new JdwpExecutionResult(
                        request.port(), JdwpCollectionCompletion.SUCCESS, true, true,
                        Optional.of(successfulRun(request.targetOptions().stdoutLog(),
                                request.targetOptions().stderrLog(), 109)),
                        Optional.of(successfulRun(request.collectorStdoutLog(),
                                request.collectorStderrLog(), 110)));
            } catch (java.io.IOException failure) {
                throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                        "TEST_IO", "fixture write failed", failure);
            }
        });

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service.execute(workspace, PROJECT_ID, CASE_ID, PLAN_ID));

        assertEquals("JDWP_MANIFEST_INVALID", failure.code());
        Path root = WorkspaceLayout.of(workspace).projectCases(PROJECT_ID)
                .resolve("case-1/collections/collection-fixed");
        JdwpCollectionManifest manifest = mapper.readJson(
                root.resolve("manifest.json"), JdwpCollectionManifest.class);
        assertTrue(manifest.targetStarted());
        assertTrue(manifest.collectorStarted());
        assertEquals(0, manifest.targetExitCode());
        assertEquals(0, manifest.collectorExitCode());
        assertTrue(Files.isRegularFile(root.resolve("raw/jdwp.jsonl")));
        assertTrue(Files.isRegularFile(root.resolve("raw/collector-manifest.json")));
    }

    @Test
    void rejectsV2ManifestWithoutTheRequiredCollectorCapabilities() throws Exception {
        JdwpCollectionApplicationService service = service(request -> {
            try {
                writeExternalArtifacts(request, "{\"schedule\":1}");
                Path external = request.collectorOutputDirectory().resolve("collection-manifest.json");
                Files.writeString(external, Files.readString(external)
                        .replace("\"schemaVersion\":\"1.0\"",
                                "\"schemaVersion\":\"2.0\","
                                + "\"collectorVersion\":\"2.0.0\","
                                + "\"rawTraceSchemaVersion\":\"2.0\","
                                + "\"capabilities\":[\"exact-method-descriptor\"]"));
                return new JdwpExecutionResult(
                        request.port(), JdwpCollectionCompletion.SUCCESS, true, true,
                        Optional.of(successfulRun(request.targetOptions().stdoutLog(),
                                request.targetOptions().stderrLog(), 111)),
                        Optional.of(successfulRun(request.collectorStdoutLog(),
                                request.collectorStderrLog(), 112)));
            } catch (java.io.IOException failure) {
                throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                        "TEST_IO", "fixture write failed", failure);
            }
        });

        CaseRunException failure = assertThrows(CaseRunException.class, () ->
                service.execute(workspace, PROJECT_ID, CASE_ID, PLAN_ID));

        assertEquals("JDWP_MANIFEST_INVALID", failure.code());
    }

    private JdwpCollectionApplicationService service(JdwpCollectionExecutor executor) {
        return new JdwpCollectionApplicationService(
                new ProjectRegistrationRepository(mapper, writer), mapper, writer,
                new AdapterCatalog(List.of(new StubAdapter())), new OpaqueIdGenerator(() -> "fixed"),
                fixedClock(), Optional.of(maven), java,
                new JdwpToolConfiguration(collectorJar, "1.0.0"),
                executor, () -> 51234);
    }

    private void writeExternalArtifacts(
            org.example.algorithmdebug.jdwp.JdwpExecutionRequest request, String gantt)
            throws java.io.IOException {
        Files.createDirectories(request.collectorOutputDirectory());
        Files.writeString(request.rawTracePath(), """
                {"schemaVersion":"1.0","sessionId":"jdwp-plan-1","sequence":1,"timestamp":"2026-08-18T00:00:00Z","eventType":"tracepoint_hit","tracepointId":"target-entry","hit":1,"thread":{"id":1,"name":"main"},"location":{"className":"fixture.TargetTest","methodName":"caseUnderTest","line":3,"codeIndex":0},"frames":[{"index":0,"className":"fixture.TargetTest","methodName":"caseUnderTest","line":3}]}
                """);
        Files.writeString(request.collectorOutputDirectory().resolve("collection-manifest.json"), """
                {"schemaVersion":"1.0","sessionId":"jdwp-plan-1",
                 "target":{"host":"127.0.0.1","port":51234},
                 "plan":"C:/raw/collector-plan.json","trace":"C:/raw/raw-trace.jsonl",
                 "startedAt":"2026-08-18T00:00:00Z","finishedAt":"2026-08-18T00:00:01Z",
                 "completionReason":"vm_death","eventCount":1,
                 "hitCounts":{"target-entry":1},
                 "installedLocations":{"target-entry":1}}
                """);
        Files.writeString(request.targetOptions().stdoutLog(), "target\n");
        Files.writeString(request.targetOptions().stderrLog(), "");
        Files.writeString(request.collectorStdoutLog(), "collector\n");
        Files.writeString(request.collectorStderrLog(), "");
        Files.writeString(scheduleOutput.resolve("result.json"), gantt);
    }

    private JdwpExecutionResult successfulExecution(
            org.example.algorithmdebug.jdwp.JdwpExecutionRequest request,
            String gantt,
            long targetPid,
            long collectorPid) throws org.example.algorithmdebug.jdwp.JdwpAdapterException {
        try {
            writeExternalArtifacts(request, gantt);
            return new JdwpExecutionResult(
                    request.port(), JdwpCollectionCompletion.SUCCESS, true, true,
                    Optional.of(successfulRun(request.targetOptions().stdoutLog(),
                            request.targetOptions().stderrLog(), targetPid)),
                    Optional.of(successfulRun(request.collectorStdoutLog(),
                            request.collectorStderrLog(), collectorPid)));
        } catch (java.io.IOException failure) {
            throw new org.example.algorithmdebug.jdwp.JdwpAdapterException(
                    "TEST_IO", "测试产物写入失败", failure);
        }
    }

    private RunResult successfulRun(Path stdout, Path stderr, long pid) throws java.io.IOException {
        return new RunResult(
                RunCompletion.SUCCEEDED, OptionalInt.of(0), NOW, NOW, Duration.ZERO, pid,
                new RunLog(stdout, Files.size(stdout), 0, false),
                new RunLog(stderr, Files.size(stderr), 0, false),
                TerminationReport.notAttempted());
    }

    private RunResult failedRun(Path stdout, Path stderr, long pid) throws java.io.IOException {
        Files.writeString(stderr, "java.lang.AssertionError: expected schedule\n");
        return new RunResult(
                RunCompletion.FAILED, OptionalInt.of(1), NOW, NOW, Duration.ZERO, pid,
                new RunLog(stdout, Files.size(stdout), 0, false),
                new RunLog(stderr, Files.size(stderr), 0, false),
                TerminationReport.notAttempted());
    }

    private void establishBaseline(String ganttJson) throws Exception {
        RunId runId = new RunId("baseline-run");
        CaseArchiveRepository archive = archive();
        archive.startRun(new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                runId, TARGET, "UNINSTRUMENTED", NOW));
        archive.completeRun(successfulBaselineOutcome(runId));
    }

    private void establishFailureBaseline(String message) throws Exception {
        RunId runId = new RunId("baseline-run");
        CaseArchiveRepository archive = archive();
        archive.startRun(new RunRequest(
                SchemaVersions.RUN_REQUEST, CASE_ID, CONTEXT_ID, ANALYSIS_ID,
                runId, TARGET, "UNINSTRUMENTED", NOW));
        archive.completeRun(new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, runId, ProcessOutcome.FAILED,
                TestOutcome.ERROR, GanttOutcome.ABSENT,
                Optional.of(new org.example.algorithmdebug.contracts.TargetFailureDiagnostic(
                        org.example.algorithmdebug.contracts.FailureCategory.TEST_ERROR,
                        "java.lang.IllegalStateException", message, "",
                        "fixture.Algorithm.solve(Algorithm.java:42)")), Optional.empty(),
                ComparisonOutcome.NOT_COMPARED, "not compared", List.of()));
        var diagnostic = new org.example.algorithmdebug.contracts.TargetFailureDiagnostic(
                org.example.algorithmdebug.contracts.FailureCategory.TEST_ERROR,
                "java.lang.IllegalStateException", message, "",
                "fixture.Algorithm.solve(Algorithm.java:42)");
        RunResultFingerprint fingerprint = new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT, CASE_ID, CONTEXT_ID, runId,
                new org.example.algorithmdebug.harness.TargetFailureFingerprinter()
                        .sha256(diagnostic));
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

    private CaseArchiveRepository archive() {
        return new CaseArchiveRepository(
                WorkspaceLayout.of(workspace).projectCases(PROJECT_ID), mapper, writer);
    }

    private static byte[] read(Path path) {
        try { return Files.readAllBytes(path); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    private static RunOutcomeSummary successfulBaselineOutcome(RunId runId) {
        return new RunOutcomeSummary(
                SchemaVersions.RUN_OUTCOME_SUMMARY, "TARGET_TEST_RUN_COMPLETED", CASE_ID,
                CONTEXT_ID, ANALYSIS_ID, runId, ProcessOutcome.SUCCEEDED,
                TestOutcome.PASSED, GanttOutcome.ABSENT, Optional.empty(), Optional.empty(),
                ComparisonOutcome.NOT_COMPARED, "not compared", List.of());
    }
    private static String portable(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private record Snapshot(String schemaVersion, String value) implements ScheduleResultSnapshot {}

    private final class StubAdapter implements TargetProjectAdapter {
        @Override public AdapterDescriptor descriptor() {
            return new AdapterDescriptor("fixture", "1.0", "fixture", Set.of(
                    AdapterCapability.BASELINE_EXECUTION));
        }
        @Override public ProjectDescriptor inspect(Path root) {
            return new ProjectDescriptor(PROJECT_ID, "fixture", root.toAbsolutePath(),
                    BuildTool.MAVEN, Path.of("pom.xml"));
        }
        @Override public TestLaunchSpec createLaunchSpec(
                ProjectDescriptor project, TargetTest test, RunMode mode) {
            return new TestLaunchSpec(project, test, mode, List.of("test"),
                    Map.of("test", test.selector()), List.of(), Duration.ofSeconds(10));
        }
        public ScheduleResultSource scheduleResultSource(
                ProjectDescriptor project, TargetTest test) {
            return new ScheduleResultSource(scheduleOutput, false);
        }
        public ScheduleResultParser<Snapshot> scheduleResultParser() {
            return path -> new Snapshot("1.0", "unused");
        }
    }
}
