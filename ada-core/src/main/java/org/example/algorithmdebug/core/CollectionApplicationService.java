package org.example.algorithmdebug.core;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.ScheduleResultSnapshot;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.ReproductionComparator;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.harness.CapturedScheduleResult;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.JsonResultParser;
import org.example.algorithmdebug.harness.JsonResultSnapshot;
import org.example.algorithmdebug.harness.OutputDirectorySnapshot;
import org.example.algorithmdebug.harness.OutputDirectorySnapshotter;
import org.example.algorithmdebug.harness.OutputStabilityPolicy;
import org.example.algorithmdebug.harness.OutputStabilityWaiter;
import org.example.algorithmdebug.harness.ScheduleResultCapture;
import org.example.algorithmdebug.harness.SurefireDiagnosticException;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.MethodPathCollectionRequest;
import org.example.algorithmdebug.methodpath.MethodPathCollectionResult;
import org.example.algorithmdebug.methodpath.MethodPathCollector;
import org.example.algorithmdebug.methodpath.MethodPathManifest;
import org.example.algorithmdebug.methodpath.CollectionCompletion;
import org.example.algorithmdebug.methodpath.TargetClasspathResolver;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;

/** 缂栨帓涓€娆?CodePath 鍔ㄦ€侀噰闆嗐€丆ase 褰掓。鍜屾棤閲囬泦 Baseline 涓€鑷存€ф鏌ャ€?*/
public final class CollectionApplicationService {
    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final AdapterCatalog adapters;
    private final OpaqueIdGenerator ids;
    private final Clock clock;
    private final Optional<Path> mavenExecutable;
    private final MethodPathCollector collector;
    private final TargetClasspathResolver classpaths;
    private final Path javaExecutable;
    private final AgentExecutionLog executionLog;

    /** 娉ㄥ叆椤圭洰銆丄dapter銆両D銆佸伐鍏蜂笌澶栭儴杩涚▼閰嶇疆銆?*/
    public CollectionApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock,
            Optional<Path> mavenExecutable,
            Path javaExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpaths) {
        this(registrations, mapper, writer, adapters, ids, clock, mavenExecutable,
                javaExecutable, collector, classpaths, AgentExecutionLog.disabled());
    }

    public CollectionApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock,
            Optional<Path> mavenExecutable,
            Path javaExecutable,
            MethodPathCollector collector,
            TargetClasspathResolver classpaths,
            AgentExecutionLog executionLog) {
        if (registrations == null || mapper == null || writer == null || adapters == null
                || ids == null || clock == null || mavenExecutable == null
                || javaExecutable == null || collector == null || classpaths == null
                || executionLog == null) {
            throw new IllegalArgumentException("CollectionApplicationService 渚濊禆涓嶈兘涓虹┖");
        }
        this.registrations = registrations; this.mapper = mapper; this.writer = writer;
        this.adapters = adapters; this.ids = ids; this.clock = clock;
        this.mavenExecutable = mavenExecutable;
        this.javaExecutable = javaExecutable.toAbsolutePath().normalize();
        this.collector = collector; this.classpaths = classpaths;
        this.executionLog = executionLog;
    }

    /** 姣忔璋冪敤鍒涘缓鏂扮殑 runId/collectionId锛屼笉鑷姩閲嶈瘯鎴栬鐩栥€?*/
    public MultiArtifactBackedResult<CollectionExecutionSummary> executeCodePath(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, PlanId planId) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withPlan(planId.value());
        executionLog.info(logContext, "CollectionApplicationService", "CODEPATH_COLLECTION_STARTED",
                "STARTED", "CodePath collection started");
        WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
        ProjectRegistration registration = registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "椤圭洰灏氭湭鐧昏"));
        CaseArchiveRepository archive = new CaseArchiveRepository(
                layout.projectCases(projectId), mapper, writer);
        var plan = archive.requireCodePathPlan(caseId, planId);
        var context = archive.requireContext(caseId, plan.contextId());
        var caseManifest = archive.requireCase(caseId);
        if (!caseManifest.projectId().equals(projectId)) {
            throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case 涓嶅睘浜庢寚瀹?Project");
        }
        Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
        AdapterCatalog.AdapterSelection selection = adapters.select(
                moduleRoot, Optional.of(caseManifest.adapterId()));
        RunId runId = ids.newRunId();
        CollectionId collectionId = ids.newCollectionId();
        MethodPathCollectionRecord record = new MethodPathCollectionRecord(
                "1.0", caseId, plan.contextId(), plan.analysisId(), runId, planId,
                collectionId, plan.targetTest(), "CODEPATH", clock.instant());
        Path collectionRoot = archive.startMethodPathCollection(record);
        logContext = logContext.withAnalysis(plan.analysisId()).withRun(runId.value())
                .withCollection(collectionId.value());
        executionLog.info(logContext, "CollectionApplicationService", "COLLECTION_RECORD_CREATED",
                "CREATED", "CodePath collection request was archived");
        CollectionBaselineCheck baseline;
        MethodPathCollectionResult result = null;
        String stage = "REQUEST_ARCHIVED";
        Instant startedAt = clock.instant();
        try {
            Optional<CaptureContext> capture = prepareCapture(registration);
            Path maven = mavenExecutable.orElseThrow(() ->
                    new CaseRunException("MAVEN_NOT_FOUND", "Maven executable unavailable"));
            List<String> classpath = classpaths.resolve(maven, moduleRoot, collectionRoot);
            stage = "CLASSPATH_RESOLVED";
            result = collector.collect(new MethodPathCollectionRequest(
                    caseId, plan.contextId(), plan.analysisId(), runId, plan, collectionId,
                    Path.of(registration.mavenExecutionRoot()), collectionRoot,
                    javaExecutable, classpath, plan.targetTest().selector()));
            executionLog.info(logContext, "CollectionApplicationService", "LAUNCHER_COMPLETED",
                    result.manifest().completion().name(), "CodePath launcher completed");
            stage = "PROCESS_COMPLETED";
            baseline = checkBaseline(archive, record, result, capture, moduleRoot);
            executionLog.info(logContext, "CollectionApplicationService", "BASELINE_CHECKED",
                    baseline.outcome().name(), "CodePath baseline was evaluated");
            archiveManifest(collectionRoot, result.manifest());
        } catch (MethodPathCollectionException failure) {
            archiveManifest(collectionRoot, failureManifest(
                    collectionRoot, record, plan, CollectionCompletion.TOOL_FAILED,
                    stage, failure.code(), failure,
                    failure.processStarted(), failure.exitCode(), startedAt));
            baseline = incomparable(record, "Collection failed before baseline check: " + failure.code());
            archive.createCollectionBaselineCheck(baseline);
            throw new CaseRunException(failure.code(), "CodePath 閲囬泦澶辫触", failure);
        } catch (CaseRunException failure) {
            archiveManifest(collectionRoot, failureManifest(
                    collectionRoot, record, plan, CollectionCompletion.AGENT_FAILED,
                    stage, failure.code(), failure,
                    result, false, -1, startedAt));
            baseline = incomparable(record, "Collection did not start: " + failure.code());
            archive.createCollectionBaselineCheck(baseline);
            throw failure;
        }
        archive.createCollectionBaselineCheck(baseline);
        CollectionPostProcessingResult postProcessing = Files.isRegularFile(
                collectionRoot.resolve("raw/codepath.jsonl"))
                ? new CollectionPostProcessingService(
                        layout.projectCases(projectId), archive, mapper, writer, ids, clock)
                        .processCodePath(record, plan, result.manifest(), baseline)
                : new CollectionPostProcessingResult(false, List.of());
        executionLog.info(logContext, "CollectionApplicationService",
                "COLLECTION_POST_PROCESSING_COMPLETED",
                postProcessing.confirmationUsable() ? "USABLE" : "PARTIAL",
                "CodePath normalization, validation, and evidence processing completed");
        Path caseRoot = layout.projectCases(projectId).resolve(caseId.value());
        List<ArtifactReference> artifacts = new java.util.ArrayList<>(describeArtifacts(
                caseRoot, collectionRoot, collectionId));
        artifacts.addAll(postProcessing.artifacts());
        artifacts = List.copyOf(artifacts);
        CollectionExecutionSummary summary = new CollectionExecutionSummary(
                caseId, plan.contextId(), plan.analysisId(), runId, planId, collectionId,
                result.manifest().completion().name(), baseline.outcome(), isEvidenceUsable(
                        result.manifest().completion(), result.manifest().capturedEventCount(), baseline)
                        && postProcessing.confirmationUsable(),
                artifacts.stream().map(ArtifactReference::relativePath).toList(),
                artifacts.stream().map(ArtifactReference::artifactId).toList());
        artifacts.forEach(artifact -> archive.registerArtifact(caseId, artifact, clock.instant()));
        archive.createCollectionExecutionSummary(summary);
        executionLog.info(logContext, "CollectionApplicationService", "COLLECTION_COMPLETED",
                summary.completion(), "CodePath collection completed");
        return new MultiArtifactBackedResult<>(summary, artifacts);
    }

    static boolean isEvidenceUsable(
            CollectionCompletion completion,
            long retainedEventCount,
            CollectionBaselineCheck baseline) {
        if (completion == null || baseline == null || retainedEventCount < 0) {
            throw new IllegalArgumentException("completion, retainedEventCount and baseline must be valid");
        }
        return baseline.evidenceUsable()
                && retainedEventCount > 0
                && (completion == CollectionCompletion.SUCCESS
                        || completion == CollectionCompletion.TARGET_FAILED);
    }

    private static List<ArtifactReference> describeArtifacts(
            Path caseRoot, Path collectionRoot, CollectionId collectionId) {
        List<ArtifactReference> artifacts = new java.util.ArrayList<>();
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("collection-request.json"),
                collectionId.value() + "-request", "COLLECTION_REQUEST", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("manifest.json"),
                collectionId.value() + "-manifest", "CODEPATH_MANIFEST", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("raw/codepath.jsonl"),
                collectionId.value() + "-raw", "CODEPATH_RAW", "application/x-ndjson");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("derived/method-path.jsonl"),
                collectionId.value() + "-trace", "METHOD_PATH_TRACE", "application/x-ndjson");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("raw/gantt.json"),
                collectionId.value() + "-gantt", "GANTT_RAW", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("validation/baseline-check.json"),
                collectionId.value() + "-baseline", "COLLECTION_BASELINE", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("logs/stdout.log"),
                collectionId.value() + "-stdout", "COLLECTOR_STDOUT", "text/plain");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("logs/stderr.log"),
                collectionId.value() + "-stderr", "COLLECTOR_STDERR", "text/plain");
        return List.copyOf(artifacts);
    }

    private static void addArtifact(
            List<ArtifactReference> artifacts,
            Path caseRoot,
            Path path,
            String id,
            String type,
            String mediaType) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        Path normalizedRoot = caseRoot.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || Files.isSymbolicLink(normalized)) {
            throw new CaseRunException("COLLECTION_ARTIFACT_INVALID", "閲囬泦浜х墿璺緞闈炴硶");
        }
        try {
            artifacts.add(new ArtifactReference(
                    id, type, normalizedRoot.relativize(normalized).toString().replace('\\', '/'),
                    mediaType, existingSha(normalized).orElseThrow(), Files.size(normalized)));
        } catch (IOException failure) {
            throw new CaseRunException(
                    "COLLECTION_ARTIFACT_INVALID", "鏃犳硶鎻忚堪閲囬泦浜х墿", failure);
        }
    }

    private void archiveManifest(Path collectionRoot, MethodPathManifest manifest) {
        writer.writeNew(collectionRoot.resolve("manifest.json"), mapper.writeJson(manifest));
    }

    private MethodPathManifest failureManifest(
            Path collectionRoot,
            MethodPathCollectionRecord record,
            org.example.algorithmdebug.contracts.CodePathCollectionPlan plan,
            CollectionCompletion completion,
            String failedStage,
            String code,
            Throwable failure,
            boolean processStarted,
            int exitCode,
            Instant startedAt) {
        return failureManifest(collectionRoot, record, plan, completion, failedStage, code,
                failure, null, processStarted, exitCode, startedAt);
    }

    private MethodPathManifest failureManifest(
            Path collectionRoot,
            MethodPathCollectionRecord record,
            org.example.algorithmdebug.contracts.CodePathCollectionPlan plan,
            CollectionCompletion completion,
            String failedStage,
            String code,
            Throwable failure,
            MethodPathCollectionResult observedResult,
            boolean processStarted,
            int exitCode,
            Instant startedAt) {
        MethodPathManifest observed = observedResult == null ? null : observedResult.manifest();
        return new MethodPathManifest(
                "2.0", record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                record.planId(), record.collectionId(), "code-path-tracer", "unavailable",
                completion, "FAILED",
                observed == null ? processStarted : observed.processStarted(),
                observed == null ? exitCode : observed.exitCode(),
                completion == CollectionCompletion.TIMED_OUT,
                observed == null ? "NOT_EXECUTED" : observed.targetOutcome(),
                observed == null ? 0 : observed.testsFound(),
                observed == null ? 0 : observed.testsSucceeded(),
                observed == null ? 0 : observed.testsAborted(),
                observed == null ? 0 : observed.testsFailed(),
                observed == null ? 0 : observed.capturedEventCount(),
                existingSize(collectionRoot.resolve("raw/codepath.jsonl")),
                List.of(), Optional.of(new AgentFailureDiagnostic(
                        code, "Collection failed at " + failedStage,
                        failure.getClass().getName())),
                "raw/codepath.jsonl", "logs/stdout.log", "logs/stderr.log",
                startedAt, clock.instant());
    }

    private static long existingSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException failure) {
            return 0;
        }
    }

    private static Optional<String> existingSha(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缂哄皯 SHA-256", failure);
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缂哄皯 SHA-256", failure);
        }
    }

    private Optional<CaptureContext> prepareCapture(
            ProjectRegistration registration) {
        try {
            var source = ProjectResultSource.from(registration);
            if (source.isEmpty()) {
                return Optional.empty();
            }
            OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(20_000);
            return Optional.of(new CaptureContext(source.orElseThrow(), snapshotter,
                    snapshotter.snapshot(source.orElseThrow())));
        } catch (HarnessException failure) {
            return Optional.empty();
        }
    }

    private CollectionBaselineCheck checkBaseline(
            CaseArchiveRepository archive,
            MethodPathCollectionRecord record,
            MethodPathCollectionResult result,
            Optional<CaptureContext> captureContext,
            Path moduleRoot) {
        var completion = result.manifest().completion();
        if (completion == org.example.algorithmdebug.methodpath.CollectionCompletion.TOOL_FAILED
                || completion == org.example.algorithmdebug.methodpath.CollectionCompletion.TIMED_OUT
                || completion == org.example.algorithmdebug.methodpath.CollectionCompletion.TRUNCATED) {
            return incomparable(record, archive.findLatestCompletedRun(
                            record.caseId(), record.contextId(), record.analysisId())
                            .map(org.example.algorithmdebug.contracts.RunOutcomeSummary::runId),
                    "Dynamic collection did not complete with confirmable evidence");
        }
        try {
            if (completion == org.example.algorithmdebug.methodpath.CollectionCompletion.TARGET_FAILED) {
                return checkTargetFailureBaseline(
                        archive, record, captureContext, moduleRoot,
                        result.request().collectionDirectory());
            }
            if (captureContext.isPresent()) {
                capture(captureContext.orElseThrow(),
                        result.request().collectionDirectory().resolve("raw/gantt.json"));
            }
            var reference = archive.findLatestCompletedRun(
                    record.caseId(), record.contextId(), record.analysisId());
            if (reference.isEmpty()
                    || reference.orElseThrow().testOutcome()
                    != org.example.algorithmdebug.contracts.TestOutcome.PASSED) {
                return incomparable(record,
                        "No completed passing uninstrumented run exists for this Analysis");
            }
            return new CollectionBaselineCheck(
                    "1.0", record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                    record.collectionId(), ComparisonOutcome.NOT_COMPARED,
                    Optional.of(reference.orElseThrow().runId()), true,
                    "Successful dynamic run; Gantt content is archived but is not an evidence gate",
                    clock.instant());
        } catch (HarnessException | WorkspaceException | SurefireDiagnosticException failure) {
            return incomparable(record, "Dynamic result capture failed: "
                    + failure.getClass().getSimpleName());
        }
    }
    private CollectionBaselineCheck checkTargetFailureBaseline(
            CaseArchiveRepository archive,
            MethodPathCollectionRecord record,
            Optional<CaptureContext> captureContext,
            Path moduleRoot,
            Path collectionRoot)
            throws WorkspaceException, HarnessException, SurefireDiagnosticException {
        Optional<CapturedScheduleResult<?>> captured = captureChangedOutput(captureContext,
                collectionRoot.resolve("raw/gantt.json"));
        return new TargetFailureBaselineEvaluator(clock).evaluate(
                archive, TargetFailureBaselineEvaluator.Identity.from(record),
                moduleRoot, captured);
    }

    private Optional<CapturedScheduleResult<?>> captureChangedOutput(
            Optional<CaptureContext> context,
            Path destination) throws HarnessException {
        if (context.isEmpty()) return Optional.empty();
        CaptureContext value = context.orElseThrow();
        if (value.snapshotter().snapshot(value.source()).equals(value.before())) {
            return Optional.empty();
        }
        return Optional.of(capture(value, destination));
    }

    private CapturedScheduleResult<?> capture(CaptureContext context, Path destination)
            throws HarnessException {
        OutputDirectorySnapshot after = new OutputStabilityWaiter(
                context.snapshotter(), OutputStabilityPolicy.defaults())
                .awaitStable(context.before(), context.source());
        return new ScheduleResultCapture<JsonResultSnapshot>(context.snapshotter(), 64L * 1024 * 1024)
                .capture(context.before(), after, new JsonResultParser(), destination);
    }

    private CollectionBaselineCheck incomparable(MethodPathCollectionRecord record, String summary) {
        return incomparable(record, Optional.empty(), summary);
    }

    private CollectionBaselineCheck incomparable(
            MethodPathCollectionRecord record, Optional<RunId> referenceRunId, String summary) {
        return new CollectionBaselineCheck(
                "1.0", record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                record.collectionId(), ComparisonOutcome.INCOMPARABLE, referenceRunId,
                false, summary, clock.instant());
    }

    private record CaptureContext(
            org.example.algorithmdebug.adapter.ScheduleResultSource source,
            OutputDirectorySnapshotter snapshotter,
            OutputDirectorySnapshot before) {
    }
}
