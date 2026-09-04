package org.example.algorithmdebug.core;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.adapter.TestLaunchSpec;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.ReproductionComparator;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.GanttOutcome;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.JsonResultParser;
import org.example.algorithmdebug.harness.JsonResultSnapshot;
import org.example.algorithmdebug.harness.MavenExecutionOptions;
import org.example.algorithmdebug.harness.OutputDirectorySnapshotter;
import org.example.algorithmdebug.harness.OutputStabilityPolicy;
import org.example.algorithmdebug.harness.OutputStabilityWaiter;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.RunOutcomeAssembler;
import org.example.algorithmdebug.harness.ScheduleProducingTestRunner;
import org.example.algorithmdebug.harness.ScheduleResultCapture;
import org.example.algorithmdebug.harness.ScheduleRunResult;
import org.example.algorithmdebug.harness.SurefireDiagnosticException;
import org.example.algorithmdebug.harness.SurefireReportSnapshot;
import org.example.algorithmdebug.harness.SurefireReportSnapshotter;
import org.example.algorithmdebug.harness.SurefireTestResult;
import org.example.algorithmdebug.harness.SurefireTestResultReader;
import org.example.algorithmdebug.harness.TargetTestExecutor;
import org.example.algorithmdebug.harness.TargetFailureFingerprinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;

/** 执行一次显式 Run：先追加请求，再运行一个 UT，最后原子追加结构化结果。 */
public final class RunApplicationService {

    private static final long MAX_LOG_BYTES = 10L * 1024 * 1024;
    private static final long MAX_GANTT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SUREFIRE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_MARKER_BYTES_PER_LOG = 32 * 1024;
    private static final long MAX_FINGERPRINT_BYTES = 8L * 1024;

    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final AdapterCatalog adapters;
    private final OpaqueIdGenerator ids;
    private final Clock clock;
    private final TargetTestExecutor executor;
    private final RunArtifactArchiver artifacts;
    private final Optional<Path> mavenExecutable;
    private final SurefireReportSnapshotter surefireSnapshotter;
    private final SurefireTestResultReader surefireReader;
    private final RunOutcomeAssembler assembler;
    private final TargetFailureFingerprinter failureFingerprinter;
    private final ReproductionComparator reproductionComparator;
    private final AgentExecutionLog executionLog;

    /** 注入项目/归档、Adapter、ID、时钟、执行端口、Artifact 与 Maven 路径。 */
    public RunApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock,
            TargetTestExecutor executor,
            RunArtifactArchiver artifacts,
            Path mavenExecutable) {
        this(registrations, mapper, writer, adapters, ids, clock, executor, artifacts,
                Optional.ofNullable(mavenExecutable), AgentExecutionLog.disabled());
    }

    /** 注入可缺失的 Maven；缺失时一次 Run 会可靠收尾为 NOT_STARTED。 */
    public RunApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock,
            TargetTestExecutor executor,
            RunArtifactArchiver artifacts,
            Optional<Path> mavenExecutable) {
        this(registrations, mapper, writer, adapters, ids, clock, executor, artifacts,
                mavenExecutable, AgentExecutionLog.disabled());
    }

    public RunApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock,
            TargetTestExecutor executor,
            RunArtifactArchiver artifacts,
            Optional<Path> mavenExecutable,
            AgentExecutionLog executionLog) {
        if (registrations == null || mapper == null || writer == null || adapters == null
                || ids == null || clock == null || executor == null || artifacts == null
                || mavenExecutable == null || executionLog == null) {
            throw new IllegalArgumentException("RunApplicationService dependencies must not be null");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.adapters = adapters;
        this.ids = ids;
        this.clock = clock;
        this.executor = executor;
        this.artifacts = artifacts;
        this.mavenExecutable = mavenExecutable.map(path -> path.toAbsolutePath().normalize());
        this.surefireSnapshotter = new SurefireReportSnapshotter();
        this.surefireReader = new SurefireTestResultReader();
        this.assembler = new RunOutcomeAssembler();
        this.failureFingerprinter = new TargetFailureFingerprinter();
        this.reproductionComparator = new ReproductionComparator();
        this.executionLog = executionLog;
    }

    /**
     * 为指定 Analysis 执行一次无采集目标 UT；不自动重试，每次调用只创建一个新 Run。
     */
    public RunOutcomeSummary execute(
            Path workspaceRoot,
            ProjectId projectId,
            CaseId caseId,
            AnalysisId analysisId) {
        if (projectId == null || caseId == null || analysisId == null) {
            throw new IllegalArgumentException("run execute parameters must not be null");
        }
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withAnalysis(analysisId);
        executionLog.info(logContext, "RunApplicationService", "RUN_EXECUTION_STARTED",
                "STARTED", "Target test execution started");
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            CaseManifest manifest = requireCase(archive, caseId);
            if (!manifest.projectId().equals(projectId)) {
                throw new CaseRunException("CASE_PROJECT_MISMATCH", "The Case does not belong to the requested project");
            }
            AnalysisRequest analysis = requireAnalysis(archive, caseId, analysisId);
            archive.requireVerifiedAlgorithmInputCapture(caseId, analysisId);
            executionLog.info(logContext, "RunApplicationService", "INPUT_PRECONDITION_VERIFIED",
                    "VERIFIED", "Algorithm input precondition was verified");

            RunId runId = ids.newRunId();
            RunRequest request = new RunRequest(
                    SchemaVersions.RUN_REQUEST, caseId, analysisId, runId,
                    manifest.targetTest(), "UNINSTRUMENTED", clock.instant());
            try {
                archive.startRun(request);
                logContext = logContext.withRun(runId.value());
                executionLog.info(logContext, "RunApplicationService", "RUN_RECORD_CREATED",
                        "CREATED", "Run request was archived");
            } catch (WorkspaceException failure) {
                throw new CaseRunException(failure.code(), "Failed to create RunRequest", failure);
            }

            if (mavenExecutable.isEmpty()) {
                RunOutcomeSummary outcome = completeNotStarted(
                        archive, request, "MAVEN_NOT_FOUND",
                        new IllegalStateException("Maven executable unavailable"));
                logRunCompleted(logContext, outcome);
                return outcome;
            }

            Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
            AdapterCatalog.AdapterSelection selection;
            try {
                selection = adapters.select(
                        moduleRoot, Optional.of(manifest.adapterId()));
            } catch (CaseRunException failure) {
                return completeNotStarted(archive, request, failure.code(), failure);
            }
            try {
                return executeSelected(archive, request, selection, registration, moduleRoot, logContext);
            } catch (AdapterException failure) {
                RunOutcomeSummary outcome = completeNotStarted(archive, request, failure.code(), failure);
                logRunCompleted(logContext, outcome);
                return outcome;
            } catch (SurefireDiagnosticException failure) {
                RunOutcomeSummary outcome = completeNotStarted(
                        archive, request, "SUREFIRE_SNAPSHOT_FAILED", failure);
                logRunCompleted(logContext, outcome);
                return outcome;
            } catch (HarnessException failure) {
                if ("HARNESS_PROCESS_START_FAILED".equals(failure.code())) {
                    return completeNotStarted(archive, request, failure.code(), failure);
                }
                throw new CaseRunException(
                        failure.code(), "The UT did not produce a trustworthy process result; the Run remains incomplete",
                        failure);
            }
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Failed to read or write the Case Workspace", failure);
        }
    }

    private RunOutcomeSummary executeSelected(
            CaseArchiveRepository archive,
            RunRequest request,
            AdapterCatalog.AdapterSelection selection,
            ProjectRegistration registration,
            Path moduleRoot,
            AgentLogContext logContext) throws AdapterException, HarnessException, SurefireDiagnosticException {
        TargetProjectAdapter adapter = selection.adapter();
        TestLaunchSpec spec = adapter.createLaunchSpec(
                selection.project(), request.targetTest(), RunMode.BASELINE);
        Optional<ScheduleResultSource> resultSource = ProjectResultSource.from(registration);
        Path reports = moduleRoot.resolve("target/surefire-reports").normalize();
        SurefireReportSnapshot before = surefireSnapshotter.snapshot(reports, request.targetTest());
        Path raw = archive.runRawDirectory(request.caseId(), request.runId());
        Path caseRoot = archive.caseRoot(request.caseId());

        OutputDirectorySnapshotter outputSnapshotter = new OutputDirectorySnapshotter(20_000);
        ScheduleProducingTestRunner<JsonResultSnapshot> runner = new ScheduleProducingTestRunner<>(
                executor,
                outputSnapshotter,
                new OutputStabilityWaiter(outputSnapshotter, OutputStabilityPolicy.defaults()),
                new ScheduleResultCapture<>(outputSnapshotter, MAX_GANTT_BYTES));
        ScheduleRunResult<JsonResultSnapshot> schedule = runner.run(
                spec,
                new MavenExecutionOptions(
                        mavenExecutable.orElseThrow(),
                        raw.resolve("stdout.log"), raw.resolve("stderr.log"),
                        ProcessLimits.defaults()),
                resultSource,
                new JsonResultParser(),
                raw);
        executionLog.info(logContext, "RunApplicationService", "TARGET_PROCESS_COMPLETED",
                schedule.run().completion().name(), "Target test process completed");

        Optional<AgentFailureDiagnostic> agentFailure = schedule.agentFailure();
        Optional<SurefireTestResult> testResult = Optional.empty();
        try {
            SurefireReportSnapshot after = surefireSnapshotter.snapshot(reports, request.targetTest());
            testResult = surefireReader.read(
                    surefireSnapshotter.changedTargetReports(before, after), request.targetTest());
        } catch (SurefireDiagnosticException failure) {
            agentFailure = mergeFailure(
                    agentFailure, diagnostic("SUREFIRE_PARSE_FAILED", failure));
        }

        List<ArtifactReference> references = new ArrayList<>();
        agentFailure = referenceLogs(
                request.runId(), schedule, caseRoot, references, agentFailure);
        GanttOutcome ganttOutcome = schedule.ganttOutcome();
        if (schedule.scheduleResult().isPresent()) {
            try {
                references.add(artifacts.reference(
                        caseRoot,
                        schedule.scheduleResult().orElseThrow().capturedPath(),
                        runArtifactId(request.runId(), "gantt"),
                        "GANTT", "application/json", MAX_GANTT_BYTES));
            } catch (CaseRunException failure) {
                ganttOutcome = GanttOutcome.INCOMPLETE;
                agentFailure = mergeFailure(agentFailure,
                        diagnostic(failure.code(), failure));
            }
        }
        if (testResult.isPresent()) {
            try {
                Path report = testResult.orElseThrow().sourceReport();
                references.add(artifacts.copy(
                        caseRoot, report,
                        caseRoot.relativize(raw.resolve("surefire")
                                .resolve(report.getFileName().toString())),
                        runArtifactId(request.runId(), "surefire"),
                        "SUREFIRE_XML", "application/xml",
                        MAX_SUREFIRE_BYTES));
            } catch (CaseRunException failure) {
                agentFailure = mergeFailure(agentFailure,
                        diagnostic(failure.code(), failure));
            }
        }

        String markerText = boundedLogText(schedule.run().stdout().path())
                + "\n" + boundedLogText(schedule.run().stderr().path());
        RunOutcomeSummary observed = assembler.assemble(
                request, Optional.of(schedule.run()), testResult, ganttOutcome,
                agentFailure, markerText, references,
                ComparisonOutcome.NOT_COMPARED,
                "No valid reproduction reference");
        ComparisonDecision decision;
        try {
            Optional<RunResultFingerprint> fingerprint =
                    createFingerprint(request, schedule, observed);
            decision = archiveAndCompare(archive, caseRoot, fingerprint, references);
        } catch (HarnessException failure) {
            agentFailure = mergeFailure(agentFailure,
                    diagnostic("TARGET_FAILURE_FINGERPRINT_FAILED", failure));
            decision = incomparable("TARGET_FAILURE_FINGERPRINT_FAILED");
        } catch (CaseRunException failure) {
            agentFailure = mergeFailure(agentFailure, diagnostic(failure.code(), failure));
            decision = incomparable(failure.code());
        }
        RunOutcomeSummary outcome = assembler.assemble(
                request, Optional.of(schedule.run()), testResult, ganttOutcome,
                agentFailure, markerText, references,
                decision.outcome(), decision.summary());
        complete(archive, outcome);
        executionLog.info(logContext, "RunApplicationService", "GANTT_CAPTURE_COMPLETED",
                outcome.ganttOutcome().name(), "Gantt capture was evaluated");
        executionLog.info(logContext, "RunApplicationService", "RUN_ARTIFACTS_ARCHIVED",
                "ARCHIVED", "Run artifacts were archived",
                Map.of("artifactCount", Integer.toString(outcome.artifacts().size())));
        logRunCompleted(logContext, outcome);
        return outcome;
    }

    private void logRunCompleted(AgentLogContext context, RunOutcomeSummary outcome) {
        executionLog.info(context, "RunApplicationService", "RUN_OUTCOME_CLASSIFIED",
                outcome.testOutcome().name(), "Target test outcome was classified",
                Map.of("processOutcome", outcome.processOutcome().name(),
                        "ganttOutcome", outcome.ganttOutcome().name()));
        executionLog.info(context, "RunApplicationService", "RUN_EXECUTION_COMPLETED",
                "COMPLETED", "Target test execution completed");
    }

    private Optional<RunResultFingerprint> createFingerprint(
            RunRequest request,
            ScheduleRunResult<?> schedule,
            RunOutcomeSummary observed) throws HarnessException {
        Optional<String> failureHash = Optional.empty();
        if (observed.targetFailure().isPresent()) {
            failureHash = Optional.of(failureFingerprinter.sha256(
                    observed.targetFailure().orElseThrow()));
        }
        if (failureHash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT,
                request.caseId(), request.analysisId(), request.runId(),
                failureHash.orElseThrow()));
    }

    private ComparisonDecision archiveAndCompare(
            CaseArchiveRepository archive,
            Path caseRoot,
            Optional<RunResultFingerprint> current,
            List<ArtifactReference> references) {
        if (current.isEmpty()) {
            return new ComparisonDecision(
                    ComparisonOutcome.NOT_COMPARED, "No valid target observation");
        }
        RunResultFingerprint fingerprint = current.orElseThrow();
        try {
            Path fingerprintPath = archive.createRunResultFingerprint(fingerprint);
            references.add(artifacts.reference(
                    caseRoot, fingerprintPath,
                    runArtifactId(fingerprint.runId(), "result-fingerprint"),
                    "RUN_RESULT_FINGERPRINT",
                    "application/json", MAX_FINGERPRINT_BYTES));
            return new ComparisonDecision(
                    ComparisonOutcome.NOT_COMPARED,
                    "Failure fingerprint archived for same-analysis dynamic comparison");
        } catch (WorkspaceException | CaseRunException failure) {
            throw new CaseRunException(
                    "RUN_FINGERPRINT_WRITE_FAILED",
                    "Failed to save or reference the Run result fingerprint", failure);
        }
    }



    private static ComparisonDecision incomparable(String reasonCode) {
        return new ComparisonDecision(
                ComparisonOutcome.INCOMPARABLE,
                "Comparison INCOMPARABLE; reason=" + reasonCode);
    }



    private Optional<AgentFailureDiagnostic> referenceLogs(
            RunId runId,
            ScheduleRunResult<?> schedule,
            Path caseRoot,
            List<ArtifactReference> references,
            Optional<AgentFailureDiagnostic> current) {
        Optional<AgentFailureDiagnostic> result = current;
        try {
            references.add(artifacts.reference(
                    caseRoot, schedule.run().stdout().path(),
                    runArtifactId(runId, "stdout"),
                    "STDOUT", "text/plain", MAX_LOG_BYTES));
        } catch (CaseRunException failure) {
            result = mergeFailure(result, diagnostic(failure.code(), failure));
        }
        try {
            references.add(artifacts.reference(
                    caseRoot, schedule.run().stderr().path(),
                    runArtifactId(runId, "stderr"),
                    "STDERR", "text/plain", MAX_LOG_BYTES));
        } catch (CaseRunException failure) {
            result = mergeFailure(result, diagnostic(failure.code(), failure));
        }
        return result;
    }

    private RunOutcomeSummary completeNotStarted(
            CaseArchiveRepository archive,
            RunRequest request,
            String code,
            Throwable failure) {
        RunOutcomeSummary outcome = assembler.assemble(
                request, Optional.empty(), Optional.empty(), GanttOutcome.ABSENT,
                Optional.of(diagnostic(code, failure)), "", List.of(),
                ComparisonOutcome.NOT_COMPARED,
                "No valid target observation");
        complete(archive, outcome);
        return outcome;
    }

    private void complete(CaseArchiveRepository archive, RunOutcomeSummary outcome) {
        try {
            archive.completeRun(outcome);
            outcome.artifacts().forEach(artifact ->
                    archive.registerArtifact(outcome.caseId(), artifact, clock.instant()));
        } catch (WorkspaceException failure) {
            throw new CaseRunException(
                    "RUN_ARCHIVE_WRITE_FAILED", "Failed to write RunOutcome", failure);
        }
    }

    private ProjectRegistration requireRegistration(WorkspaceLayout layout, ProjectId projectId) {
        return registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "Project is not registered: " + projectId.value()));
    }

    private CaseArchiveRepository archive(WorkspaceLayout layout, ProjectId projectId) {
        return new CaseArchiveRepository(layout.projectCases(projectId), mapper, writer);
    }

    private static CaseManifest requireCase(CaseArchiveRepository archive, CaseId caseId) {
        try {
            return archive.requireCase(caseId);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Case does not exist or is invalid", failure);
        }
    }

    private static AnalysisRequest requireAnalysis(
            CaseArchiveRepository archive, CaseId caseId, AnalysisId analysisId) {
        try {
            return archive.requireAnalysis(caseId, analysisId);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Analysis does not exist or is invalid", failure);
        }
    }



    private static AgentFailureDiagnostic diagnostic(String code, Throwable failure) {
        return new AgentFailureDiagnostic(
                code,
                "Agent/Harness did not complete the required step; error code: " + code,
                failure == null ? "" : failure.getClass().getName());
    }

    private static Optional<AgentFailureDiagnostic> mergeFailure(
            Optional<AgentFailureDiagnostic> current,
            AgentFailureDiagnostic next) {
        if (current.isEmpty()) {
            return Optional.of(next);
        }
        AgentFailureDiagnostic first = current.orElseThrow();
        return Optional.of(new AgentFailureDiagnostic(
                "MULTIPLE_AGENT_FAILURES",
                "Multiple Agent post-processing steps are incomplete: " + first.code() + "," + next.code(),
                first.exceptionClass().isEmpty() ? next.exceptionClass() : first.exceptionClass()));
    }

    private static String runArtifactId(RunId runId, String kind) {
        return runId.value() + "-" + kind;
    }

    private static String boundedLogText(Path path) {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_MARKER_BYTES_PER_LOG);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException failure) {
            return "";
        }
    }

    private record ComparisonDecision(
            ComparisonOutcome outcome,
            String summary) {
    }
}
