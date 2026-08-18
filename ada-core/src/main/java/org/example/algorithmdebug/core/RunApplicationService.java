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
import org.example.algorithmdebug.contracts.ContextRecord;
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
                Optional.ofNullable(mavenExecutable));
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
        if (registrations == null || mapper == null || writer == null || adapters == null
                || ids == null || clock == null || executor == null || artifacts == null
                || mavenExecutable == null) {
            throw new IllegalArgumentException("RunApplicationService 依赖不能为空");
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
            throw new IllegalArgumentException("run execute 参数不能为空");
        }
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            CaseManifest manifest = requireCase(archive, caseId);
            if (!manifest.projectId().equals(projectId)) {
                throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case 不属于请求项目");
            }
            AnalysisRequest analysis = requireAnalysis(archive, caseId, analysisId);
            ContextRecord context = requireContext(archive, caseId, analysis.contextId());

            RunId runId = ids.newRunId();
            RunRequest request = new RunRequest(
                    SchemaVersions.RUN_REQUEST, caseId, context.contextId(), analysisId, runId,
                    manifest.targetTest(), "UNINSTRUMENTED", clock.instant());
            try {
                archive.startRun(request);
            } catch (WorkspaceException failure) {
                throw new CaseRunException(failure.code(), "无法创建 RunRequest", failure);
            }

            if (mavenExecutable.isEmpty()) {
                return completeNotStarted(
                        archive, request, "MAVEN_NOT_FOUND",
                        new IllegalStateException("Maven executable unavailable"));
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
                return executeSelected(archive, request, selection, moduleRoot);
            } catch (AdapterException failure) {
                return completeNotStarted(archive, request, failure.code(), failure);
            } catch (SurefireDiagnosticException failure) {
                return completeNotStarted(
                        archive, request, "SUREFIRE_SNAPSHOT_FAILED", failure);
            } catch (HarnessException failure) {
                if ("HARNESS_PROCESS_START_FAILED".equals(failure.code())) {
                    return completeNotStarted(archive, request, failure.code(), failure);
                }
                throw new CaseRunException(
                        failure.code(), "UT 执行未能形成可信进程结果，Run 保持不完整", failure);
            }
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "读取或写入 Case Workspace 失败", failure);
        }
    }

    private <T extends ScheduleResultSnapshot> RunOutcomeSummary executeSelected(
            CaseArchiveRepository archive,
            RunRequest request,
            AdapterCatalog.AdapterSelection selection,
            Path moduleRoot) throws AdapterException, HarnessException, SurefireDiagnosticException {
        @SuppressWarnings("unchecked")
        TargetProjectAdapter<T> adapter = (TargetProjectAdapter<T>) selection.adapter();
        TestLaunchSpec spec = adapter.createLaunchSpec(
                selection.project(), request.targetTest(), RunMode.BASELINE);
        ScheduleResultSource resultSource = adapter.scheduleResultSource(
                selection.project(), request.targetTest());
        Path reports = moduleRoot.resolve("target/surefire-reports").normalize();
        SurefireReportSnapshot before = surefireSnapshotter.snapshot(reports, request.targetTest());
        Path raw = archive.runRawDirectory(request.caseId(), request.runId());

        OutputDirectorySnapshotter outputSnapshotter = new OutputDirectorySnapshotter(20_000);
        ScheduleProducingTestRunner<T> runner = new ScheduleProducingTestRunner<>(
                executor,
                outputSnapshotter,
                new OutputStabilityWaiter(outputSnapshotter, OutputStabilityPolicy.defaults()),
                new ScheduleResultCapture<>(outputSnapshotter, MAX_GANTT_BYTES));
        ScheduleRunResult<T> schedule = runner.run(
                spec,
                new MavenExecutionOptions(
                        mavenExecutable.orElseThrow(),
                        raw.resolve("stdout.log"), raw.resolve("stderr.log"),
                        ProcessLimits.defaults()),
                resultSource,
                adapter.scheduleResultParser(),
                raw.resolve("gantt.json"));

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
        agentFailure = referenceLogs(schedule, raw, references, agentFailure);
        GanttOutcome ganttOutcome = schedule.ganttOutcome();
        if (schedule.scheduleResult().isPresent()) {
            try {
                references.add(artifacts.reference(
                        raw.getParent(),
                        schedule.scheduleResult().orElseThrow().capturedPath(),
                        "artifact-gantt", "GANTT", "application/json", MAX_GANTT_BYTES));
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
                        raw.getParent(), report,
                        Path.of("raw", "surefire", report.getFileName().toString()),
                        "artifact-surefire", "SUREFIRE_XML", "application/xml",
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
            decision = archiveAndCompare(archive, fingerprint, references);
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
        return outcome;
    }

    private Optional<RunResultFingerprint> createFingerprint(
            RunRequest request,
            ScheduleRunResult<?> schedule,
            RunOutcomeSummary observed) throws HarnessException {
        Optional<String> rawHash = Optional.empty();
        Optional<String> normalizedHash = Optional.empty();
        if (observed.ganttOutcome() == GanttOutcome.PRESENT
                && schedule.scheduleResult().isPresent()) {
            rawHash = Optional.of(schedule.scheduleResult().orElseThrow().rawSha256());
            normalizedHash = Optional.of(
                    schedule.scheduleResult().orElseThrow().normalizedJsonSha256());
        }
        Optional<String> failureHash = Optional.empty();
        if (observed.targetFailure().isPresent()) {
            failureHash = Optional.of(failureFingerprinter.sha256(
                    observed.targetFailure().orElseThrow()));
        }
        if (rawHash.isEmpty() && failureHash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RunResultFingerprint(
                SchemaVersions.RUN_RESULT_FINGERPRINT,
                request.caseId(), request.contextId(), request.runId(),
                rawHash, normalizedHash, failureHash));
    }

    private ComparisonDecision archiveAndCompare(
            CaseArchiveRepository archive,
            Optional<RunResultFingerprint> current,
            List<ArtifactReference> references) {
        if (current.isEmpty()) {
            return new ComparisonDecision(
                    ComparisonOutcome.NOT_COMPARED, "No valid target observation");
        }
        RunResultFingerprint fingerprint = current.orElseThrow();
        Path fingerprintPath;
        try {
            fingerprintPath = archive.createRunResultFingerprint(fingerprint);
            references.add(artifacts.reference(
                    fingerprintPath.getParent(), fingerprintPath,
                    "artifact-run-result-fingerprint", "RUN_RESULT_FINGERPRINT",
                    "application/json", MAX_FINGERPRINT_BYTES));
        } catch (WorkspaceException | CaseRunException failure) {
            throw new CaseRunException(
                    "RUN_FINGERPRINT_WRITE_FAILED", "无法保存或引用 Run 结果指纹", failure);
        }

        Optional<RunResultFingerprint> sameContext;
        try {
            sameContext = archive.findReproduction(
                    fingerprint.caseId(), fingerprint.contextId());
        } catch (WorkspaceException failure) {
            throw new CaseRunException(
                    "REPRODUCTION_REFERENCE_INVALID", "当前 Context 参考无效", failure);
        }
        if (sameContext.isPresent()) {
            return compare(sameContext.orElseThrow(), fingerprint,
                    ReproductionComparator.Scope.SAME_CONTEXT);
        }

        Optional<RunResultFingerprint> previous;
        try {
            previous = archive.findLatestReproductionBefore(
                    fingerprint.caseId(), fingerprint.contextId());
        } catch (WorkspaceException failure) {
            throw new CaseRunException(
                    "REPRODUCTION_REFERENCE_INVALID", "旧 Context 参考无效", failure);
        }

        RunResultFingerprint established;
        try {
            established = archive.createReproductionIfAbsent(fingerprint);
        } catch (WorkspaceException failure) {
            if (isInvalidReferenceFailure(failure)) {
                throw new CaseRunException(
                        "REPRODUCTION_REFERENCE_INVALID", "当前 Context 参考无效", failure);
            }
            throw new CaseRunException(
                    "REPRODUCTION_REFERENCE_WRITE_FAILED", "无法建立当前 Context 参考", failure);
        }
        if (!established.equals(fingerprint)) {
            return compare(established, fingerprint,
                    ReproductionComparator.Scope.SAME_CONTEXT);
        }
        if (previous.isPresent()) {
            return compare(previous.orElseThrow(), fingerprint,
                    ReproductionComparator.Scope.CROSS_CONTEXT);
        }
        return new ComparisonDecision(
                ComparisonOutcome.NOT_COMPARED,
                "No prior reproduction reference; current run stored as context reference");
    }

    private ComparisonDecision compare(
            RunResultFingerprint reference,
            RunResultFingerprint current,
            ReproductionComparator.Scope scope) {
        try {
            ReproductionComparator.Result result =
                    reproductionComparator.compare(reference, current, scope);
            return new ComparisonDecision(result.outcome(), result.summary());
        } catch (RuntimeException failure) {
            throw new CaseRunException(
                    "RUN_COMPARISON_INCOMPARABLE", "Run 结果指纹无法可信比较", failure);
        }
    }

    private static ComparisonDecision incomparable(String reasonCode) {
        return new ComparisonDecision(
                ComparisonOutcome.INCOMPARABLE,
                "Comparison INCOMPARABLE; reason=" + reasonCode);
    }

    private static boolean isInvalidReferenceFailure(WorkspaceException failure) {
        return "CASE_DOCUMENT_INVALID".equals(failure.code())
                || "CASE_ARCHIVE_IDENTITY_MISMATCH".equals(failure.code())
                || "RUN_NOT_FOUND".equals(failure.code());
    }

    private Optional<AgentFailureDiagnostic> referenceLogs(
            ScheduleRunResult<?> schedule,
            Path raw,
            List<ArtifactReference> references,
            Optional<AgentFailureDiagnostic> current) {
        Optional<AgentFailureDiagnostic> result = current;
        try {
            references.add(artifacts.reference(
                    raw.getParent(), schedule.run().stdout().path(),
                    "artifact-stdout", "STDOUT", "text/plain", MAX_LOG_BYTES));
        } catch (CaseRunException failure) {
            result = mergeFailure(result, diagnostic(failure.code(), failure));
        }
        try {
            references.add(artifacts.reference(
                    raw.getParent(), schedule.run().stderr().path(),
                    "artifact-stderr", "STDERR", "text/plain", MAX_LOG_BYTES));
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

    private static void complete(CaseArchiveRepository archive, RunOutcomeSummary outcome) {
        try {
            archive.completeRun(outcome);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(
                    "RUN_ARCHIVE_WRITE_FAILED", "无法写入 RunOutcome", failure);
        }
    }

    private ProjectRegistration requireRegistration(WorkspaceLayout layout, ProjectId projectId) {
        return registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "项目尚未登记: " + projectId.value()));
    }

    private CaseArchiveRepository archive(WorkspaceLayout layout, ProjectId projectId) {
        return new CaseArchiveRepository(layout.projectCases(projectId), mapper, writer);
    }

    private static CaseManifest requireCase(CaseArchiveRepository archive, CaseId caseId) {
        try {
            return archive.requireCase(caseId);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Case 不存在或无效", failure);
        }
    }

    private static AnalysisRequest requireAnalysis(
            CaseArchiveRepository archive, CaseId caseId, AnalysisId analysisId) {
        try {
            return archive.requireAnalysis(caseId, analysisId);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Analysis 不存在或无效", failure);
        }
    }

    private static ContextRecord requireContext(
            CaseArchiveRepository archive,
            CaseId caseId,
            org.example.algorithmdebug.contracts.ContextId contextId) {
        try {
            return archive.requireContext(caseId, contextId);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Context 不存在或无效", failure);
        }
    }

    private static AgentFailureDiagnostic diagnostic(String code, Throwable failure) {
        return new AgentFailureDiagnostic(
                code,
                "Agent/Harness 未完成相应步骤；错误码: " + code,
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
                "多个 Agent 后处理步骤未完成: " + first.code() + "," + next.code(),
                first.exceptionClass().isEmpty() ? next.exceptionClass() : first.exceptionClass()));
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
