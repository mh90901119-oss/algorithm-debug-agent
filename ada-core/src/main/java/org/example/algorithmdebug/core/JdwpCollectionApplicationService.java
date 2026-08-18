package org.example.algorithmdebug.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.RunMode;
import org.example.algorithmdebug.adapter.TargetProjectAdapter;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.ReproductionComparator;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AgentFailureDiagnostic;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.ComparisonOutcome;
import org.example.algorithmdebug.contracts.JdwpCollectionCompletion;
import org.example.algorithmdebug.contracts.JdwpCollectionManifest;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.JdwpCollectionStage;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.harness.CapturedScheduleResult;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.MavenExecutionOptions;
import org.example.algorithmdebug.harness.OutputDirectorySnapshot;
import org.example.algorithmdebug.harness.OutputDirectorySnapshotter;
import org.example.algorithmdebug.harness.OutputStabilityPolicy;
import org.example.algorithmdebug.harness.OutputStabilityWaiter;
import org.example.algorithmdebug.harness.ProcessLimits;
import org.example.algorithmdebug.harness.ScheduleResultCapture;
import org.example.algorithmdebug.harness.SurefireDiagnosticException;
import org.example.algorithmdebug.jdwp.JdwpAdapterException;
import org.example.algorithmdebug.jdwp.JdwpExecutionRequest;
import org.example.algorithmdebug.jdwp.JdwpExecutionResult;
import org.example.algorithmdebug.plan.CollectorDebugPlanWriter;

/** 编排一次追加式 JDWP 采集、原始产物归档和无采集 Baseline 一致性检查。 */
public final class JdwpCollectionApplicationService {
    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final AdapterCatalog adapters;
    private final OpaqueIdGenerator ids;
    private final Clock clock;
    private final Optional<Path> mavenExecutable;
    private final Path javaExecutable;
    private final JdwpToolConfiguration tool;
    private final JdwpCollectionExecutor executor;
    private final JdwpPortProvider ports;
    private final CollectorDebugPlanWriter collectorPlans = new CollectorDebugPlanWriter();

    /** 注入全部机器边界，以便确定性测试端口、进程和源码漂移分支。 */
    public JdwpCollectionApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock,
            Optional<Path> mavenExecutable,
            Path javaExecutable,
            JdwpToolConfiguration tool,
            JdwpCollectionExecutor executor,
            JdwpPortProvider ports) {
        if (registrations == null || mapper == null || writer == null || adapters == null
                || ids == null || clock == null || mavenExecutable == null
                || javaExecutable == null || tool == null || executor == null
                || ports == null) {
            throw new IllegalArgumentException("JDWP Collection 用例依赖不能为空");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.adapters = adapters;
        this.ids = ids;
        this.clock = clock;
        this.mavenExecutable = mavenExecutable;
        this.javaExecutable = javaExecutable.toAbsolutePath().normalize();
        this.tool = tool;
        this.executor = executor;
        this.ports = ports;
    }

    /** 每次调用创建新的 runId/collectionId，不覆盖同一 Case 的任何历史采集。 */
    public MultiArtifactBackedResult<CollectionExecutionSummary> execute(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, PlanId planId) {
        WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
        var registration = registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "项目尚未登记"));
        CaseArchiveRepository archive = new CaseArchiveRepository(
                layout.projectCases(projectId), mapper, writer);
        JdwpCollectionPlan plan = archive.requireJdwpPlan(caseId, planId);
        var context = archive.requireContext(caseId, plan.contextId());
        var caseManifest = archive.requireCase(caseId);
        if (!caseManifest.projectId().equals(projectId)) {
            throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case 不属于指定 Project");
        }
        Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
        AdapterCatalog.AdapterSelection selection = adapters.select(
                moduleRoot, Optional.of(caseManifest.adapterId()));
        RunId runId = ids.newRunId();
        CollectionId collectionId = ids.newCollectionId();
        JdwpCollectionRecord record = new JdwpCollectionRecord(
                SchemaVersions.JDWP_COLLECTION_REQUEST, caseId, plan.contextId(), plan.analysisId(),
                runId, planId, collectionId, plan.targetTest(), "JDWP", clock.instant());
        Path collectionRoot = archive.startJdwpCollection(record);
        Instant startedAt = clock.instant();
        CollectionBaselineCheck baseline;
        JdwpCollectionManifest manifest;
        boolean observedTargetStarted = false;
        boolean observedCollectorStarted = false;
        int observedTargetExitCode = -1;
        int observedCollectorExitCode = -1;
        try {
            Optional<CaptureContext> capture = prepareCapture(selection, plan.targetTest());
            Path maven = mavenExecutable.orElseThrow(() ->
                    new CaseRunException("MAVEN_NOT_FOUND", "Maven executable unavailable"));
            int port = ports.allocate();
            Path collectorPlan = collectionRoot.resolve("collector-plan.json");
            byte[] collectorPlanBytes = collectorPlans.write(plan, port);
            writer.writeNew(collectorPlan, collectorPlanBytes);
            var launch = selection.adapter().createLaunchSpec(
                    selection.project(), plan.targetTest(), RunMode.JDWP);
            JdwpExecutionRequest request = new JdwpExecutionRequest(
                    launch,
                    new MavenExecutionOptions(
                            maven, collectionRoot.resolve("logs/target-stdout.log"),
                            collectionRoot.resolve("logs/target-stderr.log"), ProcessLimits.defaults()),
                    port, javaExecutable, tool.collectorJar(), collectorPlan,
                    collectionRoot.resolve("raw"),
                    collectionRoot.resolve("logs/collector-stdout.log"),
                    collectionRoot.resolve("logs/collector-stderr.log"), ProcessLimits.defaults(),
                    plan.budget().maxBytes(), Duration.ofSeconds(30),
                    Duration.ofMillis(plan.budget().timeoutMillis()));
            JdwpExecutionResult result = executor.execute(request);
            observedTargetStarted = result.targetStarted();
            observedCollectorStarted = result.collectorStarted();
            observedTargetExitCode = exitCode(result.target());
            observedCollectorExitCode = exitCode(result.collector());
            ExternalCollectorManifest external = archiveExternalOutputs(
                    collectionRoot, request, result.completion(), plan);
            baseline = checkBaseline(
                    archive, record, result.completion(), capture, collectionRoot, moduleRoot);
            archive.createJdwpCollectionBaselineCheck(baseline);
            manifest = successManifest(
                    record, result, external, collectorPlanBytes, collectionRoot,
                    baseline, startedAt);
            writer.writeNew(collectionRoot.resolve("manifest.json"), mapper.writeJson(manifest));
        } catch (JdwpAdapterException failure) {
            manifest = failureManifest(collectionRoot, record, plan, JdwpCollectionCompletion.TOOL_FAILED,
                    failure.code(), failure, failure.targetStarted(), failure.collectorStarted(),
                    -1, -1, startedAt);
            baseline = incomparable(record, "JDWP tool failed before baseline check: " + failure.code());
            archiveFailureDocuments(archive, collectionRoot, manifest, baseline);
            throw new CaseRunException(failure.code(), "JDWP 采集失败", failure);
        } catch (AdapterException failure) {
            manifest = failureManifest(collectionRoot, record, plan, JdwpCollectionCompletion.AGENT_FAILED,
                    "JDWP_LAUNCH_SPEC_FAILED", failure, false, false, -1, -1, startedAt);
            baseline = incomparable(record, "JDWP launch specification failed");
            archiveFailureDocuments(archive, collectionRoot, manifest, baseline);
            throw new CaseRunException("JDWP_LAUNCH_SPEC_FAILED", "无法创建 JDWP 启动规格", failure);
        } catch (CaseRunException failure) {
            manifest = failureManifest(collectionRoot, record, plan, JdwpCollectionCompletion.AGENT_FAILED,
                    failure.code(), failure, observedTargetStarted, observedCollectorStarted,
                    observedTargetExitCode, observedCollectorExitCode, startedAt);
            baseline = incomparable(record, observedTargetStarted
                    ? "JDWP post-processing failed after target execution: " + failure.code()
                    : "JDWP collection did not start: " + failure.code());
            archiveFailureDocuments(archive, collectionRoot, manifest, baseline);
            throw failure;
        } catch (IOException | WorkspaceException failure) {
            manifest = failureManifest(collectionRoot, record, plan, JdwpCollectionCompletion.AGENT_FAILED,
                    "JDWP_ARCHIVE_FAILED", failure, observedTargetStarted, observedCollectorStarted,
                    observedTargetExitCode, observedCollectorExitCode, startedAt);
            baseline = incomparable(record, "JDWP artifact validation or archive failed");
            archiveFailureDocuments(archive, collectionRoot, manifest, baseline);
            throw new CaseRunException("JDWP_ARCHIVE_FAILED", "JDWP 产物归档失败", failure);
        }

        Path caseRoot = layout.projectCases(projectId).resolve(caseId.value());
        CollectionPostProcessingResult postProcessing = Files.isRegularFile(
                collectionRoot.resolve("raw/jdwp.jsonl"))
                ? new CollectionPostProcessingService(
                        layout.projectCases(projectId), archive, mapper, writer, ids, clock)
                        .processJdwp(record, plan, manifest, baseline)
                : new CollectionPostProcessingResult(false, List.of());
        List<ArtifactReference> artifacts = new ArrayList<>(describeArtifacts(
                caseRoot, collectionRoot, plan, collectionId));
        artifacts.addAll(postProcessing.artifacts());
        artifacts = List.copyOf(artifacts);
        boolean usable = (manifest.completion() == JdwpCollectionCompletion.SUCCESS
                || manifest.completion() == JdwpCollectionCompletion.TARGET_FAILED)
                && manifest.eventCount() > 0 && !manifest.truncated()
                && baseline.evidenceUsable() && postProcessing.confirmationUsable();
        CollectionExecutionSummary summary = new CollectionExecutionSummary(
                caseId, plan.contextId(), plan.analysisId(), runId, planId, collectionId,
                manifest.completion().name(), baseline.outcome(), usable,
                artifacts.stream().map(ArtifactReference::relativePath).toList());
        artifacts.forEach(artifact -> archive.registerArtifact(caseId, artifact, clock.instant()));
        archive.createCollectionExecutionSummary(summary);
        return new MultiArtifactBackedResult<>(summary, artifacts);
    }

    private void archiveFailureDocuments(
            CaseArchiveRepository archive,
            Path collectionRoot,
            JdwpCollectionManifest manifest,
            CollectionBaselineCheck baseline) {
        try {
            Path manifestPath = collectionRoot.resolve("manifest.json");
            Path baselinePath = collectionRoot.resolve("validation/baseline-check.json");
            if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                writer.writeNew(manifestPath, mapper.writeJson(manifest));
            }
            if (!Files.exists(baselinePath, LinkOption.NOFOLLOW_LINKS)) {
                archive.createJdwpCollectionBaselineCheck(baseline);
            }
        } catch (WorkspaceException persistenceFailure) {
            throw new CaseRunException(
                    "JDWP_ARCHIVE_FAILED", "JDWP 失败诊断无法安全归档", persistenceFailure);
        }
    }

    private ExternalCollectorManifest archiveExternalOutputs(
            Path collectionRoot,
            JdwpExecutionRequest request,
            JdwpCollectionCompletion completion,
            JdwpCollectionPlan plan) throws IOException {
        Path raw = request.rawTracePath();
        Path externalManifest = request.collectorOutputDirectory().resolve("collection-manifest.json");
        boolean rawPresent = Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS);
        boolean manifestPresent = Files.isRegularFile(externalManifest, LinkOption.NOFOLLOW_LINKS);
        if ((completion == JdwpCollectionCompletion.SUCCESS
                || completion == JdwpCollectionCompletion.TARGET_FAILED)
                && (!rawPresent || !manifestPresent)) {
            throw new CaseRunException(
                    "JDWP_MANIFEST_INVALID", "JDWP Collector 未生成完整 Raw Trace 和 Manifest");
        }
        Path archivedRaw = collectionRoot.resolve("raw/jdwp.jsonl");
        Path archivedManifest = collectionRoot.resolve("raw/collector-manifest.json");
        if (rawPresent) {
            Files.move(raw, archivedRaw);
        }
        if (manifestPresent) {
            Files.move(externalManifest, archivedManifest);
        }
        return manifestPresent
                ? readAndValidateExternalManifest(archivedManifest, plan, request.port())
                : ExternalCollectorManifest.empty(plan.planId().value());
    }

    private ExternalCollectorManifest readAndValidateExternalManifest(
            Path document, JdwpCollectionPlan plan, int expectedPort) {
        try {
            ExternalCollectorManifest external = mapper.readJson(
                    document, ExternalCollectorManifest.class);
            if (!"1.0".equals(external.schemaVersion())
                    || !plan.planId().value().equals(external.sessionId())
                    || !"127.0.0.1".equals(external.target().host())
                    || external.target().port() != expectedPort) {
                throw new IllegalArgumentException("schemaVersion、sessionId 或 target endpoint 与本次执行不一致");
            }
            var allowed = plan.tracepoints().stream()
                    .map(point -> point.tracepointId()).collect(java.util.stream.Collectors.toSet());
            if (!allowed.containsAll(external.hitCounts().keySet())
                    || !allowed.containsAll(external.installedLocations().keySet())) {
                throw new IllegalArgumentException("Manifest 包含计划外 tracepoint");
            }
            return external;
        } catch (WorkspaceException | IllegalArgumentException failure) {
            throw new CaseRunException(
                    "JDWP_MANIFEST_INVALID", "外部 JDWP Collector Manifest 无效", failure);
        }
    }

    private JdwpCollectionManifest successManifest(
            JdwpCollectionRecord record,
            JdwpExecutionResult result,
            ExternalCollectorManifest external,
            byte[] collectorPlanBytes,
            Path root,
            CollectionBaselineCheck baseline,
            Instant startedAt) {
        Path raw = root.resolve("raw/jdwp.jsonl");
        int targetExit = result.target().flatMap(run -> run.exitCode().isPresent()
                ? Optional.of(run.exitCode().getAsInt()) : Optional.empty()).orElse(-1);
        int collectorExit = result.collector().flatMap(run -> run.exitCode().isPresent()
                ? Optional.of(run.exitCode().getAsInt()) : Optional.empty()).orElse(-1);
        JdwpCollectionStage stage = baseline != null
                ? JdwpCollectionStage.BASELINE_CHECKED : JdwpCollectionStage.PROCESS_COMPLETED;
        Optional<AgentFailureDiagnostic> diagnostic = Optional.empty();
        return new JdwpCollectionManifest(
                SchemaVersions.JDWP_COLLECTION_MANIFEST, record.caseId(), record.contextId(),
                record.analysisId(), record.runId(), record.planId(), record.collectionId(),
                "jdwp-batch-collector", tool.version(), sha(collectorPlanBytes),
                result.completion(), stage, result.targetStarted(), result.collectorStarted(),
                targetExit, collectorExit,
                result.completion() == JdwpCollectionCompletion.TIMED_OUT,
                result.completion() == JdwpCollectionCompletion.TRUNCATED,
                external.eventCount(), existingSize(raw), external.hitCounts(),
                external.installedLocations(), existingSha(raw), diagnostic,
                "raw/jdwp.jsonl", "raw/collector-manifest.json",
                "logs/target-stdout.log", "logs/target-stderr.log",
                "logs/collector-stdout.log", "logs/collector-stderr.log",
                startedAt, clock.instant());
    }

    private JdwpCollectionManifest failureManifest(
            Path collectionRoot,
            JdwpCollectionRecord record,
            JdwpCollectionPlan plan,
            JdwpCollectionCompletion completion,
            String code,
            Throwable failure,
            boolean targetStarted,
            boolean collectorStarted,
            int targetExitCode,
            int collectorExitCode,
            Instant startedAt) {
        Path raw = Path.of("raw/jdwp.jsonl");
        return new JdwpCollectionManifest(
                SchemaVersions.JDWP_COLLECTION_MANIFEST, record.caseId(), record.contextId(),
                record.analysisId(), record.runId(), record.planId(), record.collectionId(),
                "jdwp-batch-collector", tool.version(),
                existingSha(collectionRoot.resolve("collector-plan.json"))
                        .orElseGet(() -> sha(mapper.writeJson(plan))),
                completion, JdwpCollectionStage.FAILED, targetStarted, collectorStarted,
                targetExitCode, collectorExitCode, completion == JdwpCollectionCompletion.TIMED_OUT,
                completion == JdwpCollectionCompletion.TRUNCATED, 0, 0, Map.of(), Map.of(),
                Optional.empty(), Optional.of(new AgentFailureDiagnostic(
                        code, "JDWP collection failed", failure.getClass().getName())),
                raw.toString().replace('\\', '/'), "raw/collector-manifest.json",
                "logs/target-stdout.log", "logs/target-stderr.log",
                "logs/collector-stdout.log", "logs/collector-stderr.log",
                startedAt, clock.instant());
    }

    private Optional<CaptureContext> prepareCapture(
            AdapterCatalog.AdapterSelection selection,
            org.example.algorithmdebug.contracts.TargetTest target) {
        try {
            var source = selection.adapter().scheduleResultSource(selection.project(), target);
            OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(20_000);
            return Optional.of(new CaptureContext(
                    selection, source, snapshotter, snapshotter.snapshot(source)));
        } catch (AdapterException | HarnessException failure) {
            return Optional.empty();
        }
    }

    private CollectionBaselineCheck checkBaseline(
            CaseArchiveRepository archive,
            JdwpCollectionRecord record,
            JdwpCollectionCompletion completion,
            Optional<CaptureContext> captureContext,
            Path collectionRoot,
            Path moduleRoot) {
        if (completion == JdwpCollectionCompletion.TRUNCATED) {
            Optional<RunId> reference = archive.findReproduction(
                    record.caseId(), record.contextId()).map(RunResultFingerprint::runId);
            return incomparable(record, reference,
                    "JDWP collection was truncated before a comparable Gantt result");
        }
        if (completion == JdwpCollectionCompletion.TOOL_FAILED
                || completion == JdwpCollectionCompletion.TIMED_OUT) {
            return incomparable(record, "No comparable Gantt observation from JDWP collection");
        }
        try {
            if (completion == JdwpCollectionCompletion.TARGET_FAILED) {
                return checkTargetFailureBaseline(
                        archive, record, captureContext, collectionRoot, moduleRoot);
            }
            if (captureContext.isEmpty()) {
                return incomparable(record, "No comparable Gantt observation from JDWP collection");
            }
            CapturedScheduleResult<?> captured = capture(
                    captureContext.orElseThrow(), collectionRoot.resolve("raw/gantt.json"));
            Optional<RunResultFingerprint> reference = archive.findReproduction(
                    record.caseId(), record.contextId());
            if (reference.isEmpty()) {
                return new CollectionBaselineCheck(
                        "1.0", record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                        record.collectionId(), ComparisonOutcome.NOT_COMPARED, Optional.empty(),
                        Optional.of(captured.normalizedJsonSha256()), false,
                        "No uninstrumented same-context reproduction reference", clock.instant());
            }
            RunResultFingerprint current = new RunResultFingerprint(
                    SchemaVersions.RUN_RESULT_FINGERPRINT, record.caseId(), record.contextId(),
                    record.runId(), Optional.of(captured.rawSha256()),
                    Optional.of(captured.normalizedJsonSha256()), Optional.empty());
            ReproductionComparator.Result compared = new ReproductionComparator().compare(
                    reference.orElseThrow(), current, ReproductionComparator.Scope.SAME_CONTEXT);
            return new CollectionBaselineCheck(
                    "1.0", record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                    record.collectionId(), compared.outcome(),
                    Optional.of(reference.orElseThrow().runId()),
                    Optional.of(captured.normalizedJsonSha256()),
                    compared.outcome() == ComparisonOutcome.MATCHED,
                    compared.summary(), clock.instant());
        } catch (HarnessException | WorkspaceException | SurefireDiagnosticException failure) {
            return incomparable(record, "Gantt capture or baseline comparison failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    private CollectionBaselineCheck checkTargetFailureBaseline(
            CaseArchiveRepository archive,
            JdwpCollectionRecord record,
            Optional<CaptureContext> captureContext,
            Path collectionRoot,
            Path moduleRoot)
            throws WorkspaceException, HarnessException, SurefireDiagnosticException {
        Optional<CapturedScheduleResult<?>> captured = captureChangedOutput(
                captureContext, collectionRoot.resolve("raw/gantt.json"));
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CapturedScheduleResult<?> capture(CaptureContext context, Path destination)
            throws HarnessException {
        OutputDirectorySnapshot after = new OutputStabilityWaiter(
                context.snapshotter(), OutputStabilityPolicy.defaults())
                .awaitStable(context.before(), context.source());
        TargetProjectAdapter adapter = context.selection().adapter();
        return new ScheduleResultCapture(context.snapshotter(), 64L * 1024 * 1024)
                .capture(context.before(), after, adapter.scheduleResultParser(), destination);
    }

    private CollectionBaselineCheck incomparable(JdwpCollectionRecord record, String summary) {
        return incomparable(record, Optional.empty(), summary);
    }

    private CollectionBaselineCheck incomparable(
            JdwpCollectionRecord record, Optional<RunId> referenceRunId, String summary) {
        return new CollectionBaselineCheck(
                "1.0", record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                record.collectionId(), ComparisonOutcome.INCOMPARABLE, referenceRunId,
                Optional.empty(), false, summary, clock.instant());
    }

    private static List<ArtifactReference> describeArtifacts(
            Path caseRoot, Path collectionRoot, JdwpCollectionPlan plan, CollectionId collectionId) {
        ArrayList<ArtifactReference> artifacts = new ArrayList<>();
        addArtifact(artifacts, caseRoot,
                caseRoot.resolve("analyses").resolve(plan.analysisId().value()).resolve("plans")
                        .resolve(plan.planId().value() + ".json"),
                plan.planId().value(), "JDWP_PLAN", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("collection-request.json"),
                collectionId.value() + "-request", "COLLECTION_REQUEST", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("collector-plan.json"),
                collectionId.value() + "-collector-plan", "JDWP_COLLECTOR_PLAN", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("manifest.json"),
                collectionId.value() + "-manifest", "JDWP_MANIFEST", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("raw/jdwp.jsonl"),
                collectionId.value() + "-raw", "JDWP_RAW", "application/x-ndjson");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("raw/collector-manifest.json"),
                collectionId.value() + "-external-manifest", "JDWP_EXTERNAL_MANIFEST", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("raw/gantt.json"),
                collectionId.value() + "-gantt", "GANTT_RAW", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("validation/baseline-check.json"),
                collectionId.value() + "-baseline", "COLLECTION_BASELINE", "application/json");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("logs/target-stdout.log"),
                collectionId.value() + "-target-out", "TARGET_STDOUT", "text/plain");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("logs/target-stderr.log"),
                collectionId.value() + "-target-err", "TARGET_STDERR", "text/plain");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("logs/collector-stdout.log"),
                collectionId.value() + "-collector-out", "COLLECTOR_STDOUT", "text/plain");
        addArtifact(artifacts, caseRoot, collectionRoot.resolve("logs/collector-stderr.log"),
                collectionId.value() + "-collector-err", "COLLECTOR_STDERR", "text/plain");
        return List.copyOf(artifacts);
    }

    private static void addArtifact(
            List<ArtifactReference> artifacts, Path caseRoot, Path path,
            String id, String type, String mediaType) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path normalizedRoot = caseRoot.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || Files.isSymbolicLink(normalized)) {
            throw new CaseRunException("COLLECTION_ARTIFACT_INVALID", "JDWP 产物路径非法");
        }
        try {
            artifacts.add(new ArtifactReference(
                    id, type, normalizedRoot.relativize(normalized).toString().replace('\\', '/'),
                    mediaType, existingSha(normalized).orElseThrow(), Files.size(normalized)));
        } catch (IOException failure) {
            throw new CaseRunException("COLLECTION_ARTIFACT_INVALID", "无法描述 JDWP 产物", failure);
        }
    }

    private static long existingSize(Path path) {
        try { return Files.isRegularFile(path) ? Files.size(path) : 0; }
        catch (IOException failure) { return 0; }
    }

    private static int exitCode(Optional<org.example.algorithmdebug.harness.RunResult> result) {
        return result.flatMap(run -> run.exitCode().isPresent()
                ? Optional.of(run.exitCode().getAsInt()) : Optional.empty()).orElse(-1);
    }

    private static Optional<String> existingSha(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(sha(Files.readAllBytes(path)));
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

    private record CaptureContext(
            AdapterCatalog.AdapterSelection selection,
            org.example.algorithmdebug.adapter.ScheduleResultSource source,
            OutputDirectorySnapshotter snapshotter,
            OutputDirectorySnapshot before) {}

    private record ExternalCollectorManifest(
            String schemaVersion,
            String sessionId,
            ExternalCollectorTarget target,
            String plan,
            String trace,
            Instant startedAt,
            Instant finishedAt,
            String completionReason,
            int eventCount,
            Map<String, Integer> hitCounts,
            Map<String, Integer> installedLocations) {
        private ExternalCollectorManifest {
            hitCounts = hitCounts == null ? Map.of() : Map.copyOf(hitCounts);
            installedLocations = installedLocations == null ? Map.of() : Map.copyOf(installedLocations);
            if (target == null || plan == null || plan.isBlank() || trace == null || trace.isBlank()
                    || startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)
                    || completionReason == null || completionReason.isBlank()
                    || eventCount < 0 || hitCounts.size() > 20 || installedLocations.size() > 20) {
                throw new IllegalArgumentException("External JDWP Manifest 超出有界契约");
            }
            if (hitCounts.values().stream().anyMatch(value -> value == null || value < 0)
                    || installedLocations.values().stream().anyMatch(
                            value -> value == null || value < 0)) {
                throw new IllegalArgumentException("External JDWP Manifest 计数无效");
            }
        }

        static ExternalCollectorManifest empty(String sessionId) {
            return new ExternalCollectorManifest(
                    "1.0", sessionId, new ExternalCollectorTarget("127.0.0.1", 1),
                    "unavailable", "unavailable", Instant.EPOCH, Instant.EPOCH,
                    "unavailable", 0, Map.of(), Map.of());
        }
    }

    private record ExternalCollectorTarget(String host, int port) {
        private ExternalCollectorTarget {
            if (host == null || host.isBlank() || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("External JDWP Manifest target invalid");
            }
        }
    }
}
