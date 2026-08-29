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
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.harness.CapturedScheduleResult;
import org.example.algorithmdebug.harness.HarnessException;
import org.example.algorithmdebug.harness.JsonResultParser;
import org.example.algorithmdebug.harness.JsonResultSnapshot;
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
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;

/** 缂栨帓涓€娆¤拷鍔犲紡 JDWP 閲囬泦銆佸師濮嬩骇鐗╁綊妗ｅ拰鏃犻噰闆?Baseline 涓€鑷存€ф鏌ャ€?*/
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
    private final AgentExecutionLog executionLog;
    private final CollectorDebugPlanWriter collectorPlans = new CollectorDebugPlanWriter();

    /** 娉ㄥ叆鍏ㄩ儴鏈哄櫒杈圭晫锛屼互渚跨‘瀹氭€ф祴璇曠鍙ｃ€佽繘绋嬪拰婧愮爜婕傜Щ鍒嗘敮銆?*/
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
        this(registrations, mapper, writer, adapters, ids, clock, mavenExecutable,
                javaExecutable, tool, executor, ports, AgentExecutionLog.disabled());
    }

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
            JdwpPortProvider ports,
            AgentExecutionLog executionLog) {
        if (registrations == null || mapper == null || writer == null || adapters == null
                || ids == null || clock == null || mavenExecutable == null
                || javaExecutable == null || tool == null || executor == null
                || ports == null || executionLog == null) {
            throw new IllegalArgumentException("JDWP collection use-case dependencies must not be null");
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
        this.executionLog = executionLog;
    }

    /** 姣忔璋冪敤鍒涘缓鏂扮殑 runId/collectionId锛屼笉瑕嗙洊鍚屼竴 Case 鐨勪换浣曞巻鍙查噰闆嗐€?*/
    public MultiArtifactBackedResult<CollectionExecutionSummary> execute(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, PlanId planId) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withPlan(planId.value());
        executionLog.info(logContext, "JdwpCollectionApplicationService", "JDWP_COLLECTION_STARTED",
                "STARTED", "JDWP collection started");
        WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
        var registration = registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "Project is not registered"));
        CaseArchiveRepository archive = new CaseArchiveRepository(
                layout.projectCases(projectId), mapper, writer);
        JdwpCollectionPlan plan = archive.requireJdwpPlan(caseId, planId);
        var context = archive.requireContext(caseId, plan.contextId());
        var caseManifest = archive.requireCase(caseId);
        if (!caseManifest.projectId().equals(projectId)) {
            throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case does not belong to the specified Project");
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
        logContext = logContext.withAnalysis(plan.analysisId()).withRun(runId.value())
                .withCollection(collectionId.value());
        executionLog.info(logContext, "JdwpCollectionApplicationService", "COLLECTION_RECORD_CREATED",
                "CREATED", "JDWP collection request was archived");
        Instant startedAt = clock.instant();
        CollectionBaselineCheck baseline;
        JdwpCollectionManifest manifest;
        boolean observedTargetStarted = false;
        boolean observedCollectorStarted = false;
        int observedTargetExitCode = -1;
        int observedCollectorExitCode = -1;
        try {
            Optional<CaptureContext> capture = prepareCapture(registration);
            Path maven = mavenExecutable.orElseThrow(() ->
                    new CaseRunException("MAVEN_NOT_FOUND", "Maven executable unavailable"));
            int port = ports.allocate();
            Path collectorPlan = collectionRoot.resolve("collector-plan.json");
            byte[] collectorPlanBytes = collectorPlans.write(plan, port);
            writer.writeNew(collectorPlan, collectorPlanBytes);
            Files.createDirectories(collectionRoot.resolve("logs"));
            Files.createDirectories(collectionRoot.resolve("raw"));
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
            executionLog.info(logContext, "JdwpCollectionApplicationService", "COLLECTOR_COMPLETED",
                    result.completion().name(), "JDWP target and collector processes completed");
            observedTargetStarted = result.targetStarted();
            observedCollectorStarted = result.collectorStarted();
            observedTargetExitCode = exitCode(result.target());
            observedCollectorExitCode = exitCode(result.collector());
            ExternalCollectorManifest external = archiveExternalOutputs(
                    collectionRoot, request, result.completion(), plan);
            JdwpCollectionCompletion effectiveCompletion = effectiveCompletion(result.completion(), external);
            baseline = checkBaseline(
                    archive, record, effectiveCompletion, capture, collectionRoot, moduleRoot);
            executionLog.info(logContext, "JdwpCollectionApplicationService", "BASELINE_CHECKED",
                    baseline.outcome().name(), "JDWP baseline was evaluated");
            archive.createJdwpCollectionBaselineCheck(baseline);
            manifest = successManifest(
                    record, result, effectiveCompletion, external, collectionRoot,
                    baseline, startedAt);
            writer.writeNew(collectionRoot.resolve("manifest.json"), mapper.writeJson(manifest));
        } catch (JdwpAdapterException failure) {
            manifest = failureManifest(collectionRoot, record, plan, JdwpCollectionCompletion.TOOL_FAILED,
                    failure.code(), failure, failure.targetStarted(), failure.collectorStarted(),
                    -1, -1, startedAt);
            baseline = incomparable(record, "JDWP tool failed before baseline check: " + failure.code());
            archiveFailureDocuments(archive, collectionRoot, manifest, baseline);
            throw new CaseRunException(failure.code(), "JDWP collection failed", failure);
        } catch (AdapterException failure) {
            manifest = failureManifest(collectionRoot, record, plan, JdwpCollectionCompletion.AGENT_FAILED,
                    "JDWP_LAUNCH_SPEC_FAILED", failure, false, false, -1, -1, startedAt);
            baseline = incomparable(record, "JDWP launch specification failed");
            archiveFailureDocuments(archive, collectionRoot, manifest, baseline);
            throw new CaseRunException("JDWP_LAUNCH_SPEC_FAILED", "Failed to create JDWP launch specification", failure);
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
            throw new CaseRunException("JDWP_ARCHIVE_FAILED", "JDWP artifact archival failed", failure);
        }

        Path caseRoot = layout.projectCases(projectId).resolve(caseId.value());
        CollectionPostProcessingResult postProcessing = Files.isRegularFile(
                collectionRoot.resolve("raw/jdwp.jsonl"))
                ? new CollectionPostProcessingService(
                        layout.projectCases(projectId), archive, mapper, writer, ids, clock)
                        .processJdwp(record, plan, manifest, baseline)
                : new CollectionPostProcessingResult(false, List.of());
        executionLog.info(logContext, "JdwpCollectionApplicationService",
                "COLLECTION_POST_PROCESSING_COMPLETED",
                postProcessing.confirmationUsable() ? "USABLE" : "PARTIAL",
                "JDWP normalization, validation, and evidence processing completed");
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
                artifacts.stream().map(ArtifactReference::relativePath).toList(),
                artifacts.stream().map(ArtifactReference::artifactId).toList());
        artifacts.forEach(artifact -> archive.registerArtifact(caseId, artifact, clock.instant()));
        archive.createCollectionExecutionSummary(summary);
        executionLog.info(logContext, "JdwpCollectionApplicationService", "COLLECTION_COMPLETED",
                summary.completion(), "JDWP collection completed");
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
                    "JDWP_ARCHIVE_FAILED", "JDWP failure diagnostic could not be archived safely", persistenceFailure);
        } finally {
            deleteIfEmpty(collectionRoot.resolve("raw"));
            deleteIfEmpty(collectionRoot.resolve("logs"));
        }
    }

    private static void deleteIfEmpty(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) Files.deleteIfExists(directory);
        } catch (IOException | SecurityException cleanupFailure) {
            // Case audit reports any surviving empty directory; cleanup must not hide the original failure.
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
                    "JDWP_MANIFEST_INVALID", "JDWP Collector did not produce a complete Raw Trace and Manifest");
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
            if (!("1.0".equals(external.schemaVersion()) || "2.0".equals(external.schemaVersion()))
                    || !plan.planId().value().equals(external.sessionId())
                    || !"127.0.0.1".equals(external.target().host())
                    || external.target().port() != expectedPort) {
                throw new IllegalArgumentException("Collector manifest identity or endpoint does not match the current plan");
            }
            if ("2.0".equals(external.schemaVersion())) {
                var requiredCapabilities = java.util.Set.of(
                        "exact-method-descriptor",
                        "code-index",
                        "typed-values",
                        "bounded-projection",
                        "tracepoint-request-group");
                if (!"2.0.0".equals(external.collectorVersion())
                        || !"2.0".equals(external.rawTraceSchemaVersion())
                        || !external.capabilities().containsAll(requiredCapabilities)) {
                    throw new IllegalArgumentException(
                            "JDWP Collector 2.0 capability handshake failed");
                }
            }
            var allowed = plan.tracepoints().stream()
                    .map(point -> point.tracepointId()).collect(java.util.stream.Collectors.toSet());
            if (!allowed.containsAll(external.hitCounts().keySet())
                    || !allowed.containsAll(external.installedLocations().keySet())) {
                throw new IllegalArgumentException("Manifest contains a tracepoint outside the plan");
            }
            return external;
        } catch (WorkspaceException | IllegalArgumentException failure) {
            throw new CaseRunException(
                    "JDWP_MANIFEST_INVALID", "External JDWP Collector Manifest is invalid", failure);
        }
    }

    private JdwpCollectionManifest successManifest(
            JdwpCollectionRecord record,
            JdwpExecutionResult result,
            JdwpCollectionCompletion completion,
            ExternalCollectorManifest external,
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
                "jdwp-batch-collector", tool.version(), completion, external.completionReason(),
                stage, result.targetStarted(), result.collectorStarted(),
                targetExit, collectorExit,
                completion == JdwpCollectionCompletion.TIMED_OUT,
                completion == JdwpCollectionCompletion.TRUNCATED,
                external.eventCount(), existingSize(raw), external.hitCounts(),
                external.installedLocations(), diagnostic,
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
                "jdwp-batch-collector", tool.version(), completion, code,
                JdwpCollectionStage.FAILED, targetStarted, collectorStarted,
                targetExitCode, collectorExitCode, completion == JdwpCollectionCompletion.TIMED_OUT,
                completion == JdwpCollectionCompletion.TRUNCATED, 0, 0, Map.of(), Map.of(),
                Optional.of(new AgentFailureDiagnostic(
                        code, "JDWP collection failed", failure.getClass().getName())),
                raw.toString().replace('\\', '/'), "raw/collector-manifest.json",
                "logs/target-stdout.log", "logs/target-stderr.log",
                "logs/collector-stdout.log", "logs/collector-stderr.log",
                startedAt, clock.instant());
    }

    private static JdwpCollectionCompletion effectiveCompletion(
            JdwpCollectionCompletion processCompletion, ExternalCollectorManifest external) {
        if (processCompletion != JdwpCollectionCompletion.SUCCESS
                && processCompletion != JdwpCollectionCompletion.TARGET_FAILED) {
            return processCompletion;
        }
        String reason = external.completionReason().toLowerCase(java.util.Locale.ROOT);
        if ("idle_timeout".equals(reason) || "max_events".equals(reason) || "max_bytes".equals(reason)
                || external.installedLocations().values().stream().mapToInt(Integer::intValue).sum() == 0) {
            return JdwpCollectionCompletion.TRUNCATED;
        }
        if ("interrupted".equals(reason) || "unavailable".equals(reason)) {
            return JdwpCollectionCompletion.TOOL_FAILED;
        }
        return processCompletion;
    }

    private Optional<CaptureContext> prepareCapture(
            ProjectRegistration registration) {
        try {
            var source = ProjectResultSource.from(registration);
            if (source.isEmpty()) {
                return Optional.empty();
            }
            OutputDirectorySnapshotter snapshotter = new OutputDirectorySnapshotter(20_000);
            return Optional.of(new CaptureContext(
                    source.orElseThrow(), snapshotter, snapshotter.snapshot(source.orElseThrow())));
        } catch (HarnessException failure) {
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
        if (completion == JdwpCollectionCompletion.TRUNCATED
                || completion == JdwpCollectionCompletion.TOOL_FAILED
                || completion == JdwpCollectionCompletion.TIMED_OUT
                || completion == JdwpCollectionCompletion.AGENT_FAILED) {
            return incomparable(record, archive.findLatestCompletedRun(
                            record.caseId(), record.contextId(), record.analysisId())
                            .map(org.example.algorithmdebug.contracts.RunOutcomeSummary::runId),
                    "JDWP collection did not complete with confirmable evidence");
        }
        try {
            if (completion == JdwpCollectionCompletion.TARGET_FAILED) {
                return checkTargetFailureBaseline(
                        archive, record, captureContext, collectionRoot, moduleRoot);
            }
            if (captureContext.isPresent()) {
                capture(captureContext.orElseThrow(), collectionRoot.resolve("raw/gantt.json"));
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
                    "Successful JDWP run; Gantt content is archived but is not an evidence gate",
                    clock.instant());
        } catch (HarnessException | WorkspaceException | SurefireDiagnosticException failure) {
            return incomparable(record, "JDWP result capture failed: "
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

    private CapturedScheduleResult<?> capture(CaptureContext context, Path destination)
            throws HarnessException {
        OutputDirectorySnapshot after = new OutputStabilityWaiter(
                context.snapshotter(), OutputStabilityPolicy.defaults())
                .awaitStable(context.before(), context.source());
        return new ScheduleResultCapture<JsonResultSnapshot>(context.snapshotter(), 64L * 1024 * 1024)
                .capture(context.before(), after, new JsonResultParser(), destination);
    }

    private CollectionBaselineCheck incomparable(JdwpCollectionRecord record, String summary) {
        return incomparable(record, Optional.empty(), summary);
    }

    private CollectionBaselineCheck incomparable(
            JdwpCollectionRecord record, Optional<RunId> referenceRunId, String summary) {
        return new CollectionBaselineCheck(
                "1.0", record.caseId(), record.contextId(), record.analysisId(), record.runId(),
                record.collectionId(), ComparisonOutcome.INCOMPARABLE, referenceRunId,
                false, summary, clock.instant());
    }

    private static List<ArtifactReference> describeArtifacts(
            Path caseRoot, Path collectionRoot, JdwpCollectionPlan plan, CollectionId collectionId) {
        ArrayList<ArtifactReference> artifacts = new ArrayList<>();
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
            throw new CaseRunException("COLLECTION_ARTIFACT_INVALID", "JDWP artifact path is invalid");
        }
        try {
            artifacts.add(new ArtifactReference(
                    id, type, normalizedRoot.relativize(normalized).toString().replace('\\', '/'),
                    mediaType, existingSha(normalized).orElseThrow(), Files.size(normalized)));
        } catch (IOException failure) {
            throw new CaseRunException("COLLECTION_ARTIFACT_INVALID", "Failed to describe JDWP artifact", failure);
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
            throw new IllegalStateException("JDK does not provide SHA-256", failure);
        }
    }

    private record CaptureContext(
            org.example.algorithmdebug.adapter.ScheduleResultSource source,
            OutputDirectorySnapshotter snapshotter,
            OutputDirectorySnapshot before) {}

    private record ExternalCollectorManifest(
            String schemaVersion,
            String collectorVersion,
            String rawTraceSchemaVersion,
            List<String> capabilities,
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
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            hitCounts = hitCounts == null ? Map.of() : Map.copyOf(hitCounts);
            installedLocations = installedLocations == null ? Map.of() : Map.copyOf(installedLocations);
            if (target == null || plan == null || plan.isBlank() || trace == null || trace.isBlank()
                    || startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)
                    || completionReason == null || completionReason.isBlank()
                    || eventCount < 0 || capabilities.size() > 32
                    || hitCounts.size() > 20 || installedLocations.size() > 20) {
                throw new IllegalArgumentException("External JDWP Manifest exceeds bounded limits");
            }
            if (hitCounts.values().stream().anyMatch(value -> value == null || value < 0)
                    || installedLocations.values().stream().anyMatch(
                            value -> value == null || value < 0)) {
                throw new IllegalArgumentException("External JDWP Manifest counts are invalid");
            }
        }

        static ExternalCollectorManifest empty(String sessionId) {
            return new ExternalCollectorManifest(
                    "1.0", null, null, List.of(), sessionId,
                    new ExternalCollectorTarget("127.0.0.1", 1),
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
