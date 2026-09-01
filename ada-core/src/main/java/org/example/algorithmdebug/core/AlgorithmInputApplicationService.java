package org.example.algorithmdebug.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.CaseArtifactAccess;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AlgorithmInputCapture;
import org.example.algorithmdebug.contracts.AlgorithmInputComparison;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.staticanalysis.AlgorithmInputLocation;
import org.example.algorithmdebug.staticanalysis.AlgorithmInputLocationException;
import org.example.algorithmdebug.staticanalysis.JavaTestAlgorithmInputLocator;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;

/** 编排目标 UT 单一算法输入的 AST 定位、原子复制、注册和多轮一致性判断。 */
public final class AlgorithmInputApplicationService {
    /** 单个算法输入文件的硬上限，256 MiB。 */
    public static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;

    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final JavaTestAlgorithmInputLocator locator;
    private final Clock clock;
    private final AgentExecutionLog executionLog;

    /** 注入确定性项目注册、归档、AST 定位和时间端口。 */
    public AlgorithmInputApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaTestAlgorithmInputLocator locator,
            Clock clock) {
        this(registrations, mapper, writer, locator, clock, AgentExecutionLog.disabled());
    }

    public AlgorithmInputApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaTestAlgorithmInputLocator locator,
            Clock clock,
            AgentExecutionLog executionLog) {
        if (registrations == null || mapper == null || writer == null
                || locator == null || clock == null || executionLog == null) {
            throw new IllegalArgumentException("Algorithm input service dependencies are required");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.locator = locator;
        this.clock = clock;
        this.executionLog = executionLog;
    }

    /**
     * 为当前 Analysis 捕获一次算法输入。同一 Analysis 重试时返回已校验的不可变归档，禁止覆盖。
     */
    public ArtifactBackedResult<AlgorithmInputCapture> capture(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withAnalysis(analysisId);
        executionLog.info(logContext, "AlgorithmInputApplicationService",
                "ALGORITHM_INPUT_CAPTURE_STARTED", "STARTED", "Algorithm input capture started");
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = registrations.findById(layout, projectId).orElseThrow(() ->
                    new CaseRunException("PROJECT_NOT_REGISTERED", "Project is not registered"));
            CaseArchiveRepository archive = new CaseArchiveRepository(
                    layout.projectCases(projectId), mapper, writer);
            var manifest = archive.requireCase(caseId);
            if (!manifest.projectId().equals(projectId)) {
                throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case belongs to another Project");
            }
            var analysis = archive.requireAnalysis(caseId, analysisId);
            var context = archive.requireContext(caseId, analysis.contextId());
            Optional<AlgorithmInputCapture> existing = archive.findAlgorithmInputCapture(caseId, analysisId);
            if (existing.isPresent()) {
                AlgorithmInputCapture verified = archive.requireVerifiedAlgorithmInputCapture(caseId, analysisId);
                executionLog.info(logContext, "AlgorithmInputApplicationService",
                        "ALGORITHM_INPUT_REUSED", "REUSED", "Existing algorithm input capture was verified");
                executionLog.info(logContext, "AlgorithmInputApplicationService",
                        "ALGORITHM_INPUT_CAPTURE_COMPLETED", "COMPLETED", "Algorithm input capture completed");
                return new ArtifactBackedResult<>(verified, verified.artifact());
            }

            Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
            AlgorithmInputLocation location = locator.locate(moduleRoot, manifest.targetTest());
            executionLog.info(logContext, "AlgorithmInputApplicationService",
                    "ALGORITHM_INPUT_LOCATED", "LOCATED", "One supported algorithm input was located");
            Path source = location.resolvedPath();
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new CaseRunException(
                        "ALGORITHM_INPUT_FILE_NOT_FOUND", "Configured algorithm input file was not found");
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
                throw new CaseRunException(
                        "ALGORITHM_INPUT_NOT_REGULAR_FILE", "Configured algorithm input is not a regular file");
            }
            long size = size(source);
            if (size > MAX_INPUT_BYTES) {
                throw new CaseRunException(
                        "ALGORITHM_INPUT_TOO_LARGE", "Algorithm input exceeds the 256 MiB limit");
            }

            Optional<AlgorithmInputCapture> previousCapture =
                    archive.findLatestAlgorithmInputCaptureBefore(caseId, analysisId);
            ArtifactReference artifact;
            AlgorithmInputComparison comparison;
            Optional<AnalysisId> previousAnalysisId;
            CaseArtifactAccess artifactAccess = new CaseArtifactAccess(layout.projectCases(projectId));
            if (previousCapture.isPresent()) {
                AlgorithmInputCapture verified = archive.requireVerifiedAlgorithmInputCapture(
                        caseId, previousCapture.orElseThrow().analysisId());
                Path archivedInput = artifactAccess.requireVerifiedArtifact(caseId, verified.artifact());
                if (!verified.fileName().equals(source.getFileName().toString())
                        || !sameContent(source, archivedInput)) {
                    throw new CaseRunException(
                            "ALGORITHM_INPUT_CHANGED",
                            "Algorithm input changed after the Case input was captured");
                }
                artifact = verified.artifact();
                comparison = AlgorithmInputComparison.UNCHANGED;
                previousAnalysisId = Optional.of(verified.analysisId());
                executionLog.info(logContext, "AlgorithmInputApplicationService",
                        "ALGORITHM_INPUT_REUSED", "REUSED",
                        "Case algorithm input Artifact was verified and reused");
            } else {
                Path copied = archive.copyAlgorithmInput(caseId, analysisId, source, MAX_INPUT_BYTES);
                executionLog.info(logContext, "AlgorithmInputApplicationService",
                        "ALGORITHM_INPUT_COPIED", "ARCHIVED",
                        "Algorithm input was copied into the Case");
                artifact = artifactAccess.describe(
                        caseId, "algorithm-input", "ALGORITHM_INPUT", "application/json", copied);
                archive.registerArtifact(caseId, artifact, clock.instant());
                comparison = AlgorithmInputComparison.FIRST_CAPTURE;
                previousAnalysisId = Optional.empty();
            }

            Path normalizedSource = location.sourceFile().toAbsolutePath().normalize();
            if (!normalizedSource.startsWith(moduleRoot)) {
                throw new CaseRunException(
                        "ALGORITHM_INPUT_SOURCE_PARSE_FAILED", "Target UT source is outside the module");
            }
            AlgorithmInputCapture capture = new AlgorithmInputCapture(
                    SchemaVersions.ALGORITHM_INPUT_CAPTURE, caseId, context.contextId(), analysisId,
                    manifest.targetTest(), location.variableName(),
                    moduleRoot.relativize(normalizedSource).toString().replace('\\', '/'),
                    location.sourceLine(), location.pathKind(), source.getFileName().toString(),
                    comparison, previousAnalysisId, artifact, clock.instant());
            archive.createAlgorithmInputCapture(capture);
            executionLog.info(logContext, "AlgorithmInputApplicationService",
                    "ALGORITHM_INPUT_CAPTURE_COMPLETED", "COMPLETED", "Algorithm input capture completed");
            return new ArtifactBackedResult<>(capture, artifact);
        } catch (AlgorithmInputLocationException failure) {
            throw new CaseRunException(failure.code(), "Algorithm input location failed", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Algorithm input archive operation failed", failure);
        }
    }

    private static boolean sameContent(Path source, Path archived) {
        try {
            return Files.mismatch(source, archived) == -1L;
        } catch (IOException | SecurityException failure) {
            throw new CaseRunException(
                    "ALGORITHM_INPUT_COPY_FAILED", "Unable to compare algorithm input", failure);
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException | SecurityException failure) {
            throw new CaseRunException(
                    "ALGORITHM_INPUT_COPY_FAILED", "Unable to inspect algorithm input", failure);
        }
    }

}
