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

/** 编排目标 UT 单一算法输入的 AST 定位、原子复制、注册和多轮一致性判断。 */
public final class AlgorithmInputApplicationService {
    /** 单个算法输入文件的硬上限，256 MiB。 */
    public static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;

    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final JavaTestAlgorithmInputLocator locator;
    private final Clock clock;

    /** 注入确定性项目注册、归档、AST 定位和时间端口。 */
    public AlgorithmInputApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaTestAlgorithmInputLocator locator,
            Clock clock) {
        if (registrations == null || mapper == null || writer == null
                || locator == null || clock == null) {
            throw new IllegalArgumentException("Algorithm input service dependencies are required");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.locator = locator;
        this.clock = clock;
    }

    /**
     * 为当前 Analysis 捕获一次算法输入。同一 Analysis 重试时返回已校验的不可变归档，禁止覆盖。
     */
    public ArtifactBackedResult<AlgorithmInputCapture> capture(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId) {
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
                return new ArtifactBackedResult<>(verified, verified.artifact());
            }

            Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
            AlgorithmInputLocation location = locator.locate(moduleRoot, manifest.targetTest());
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

            Path copied = archive.copyAlgorithmInput(caseId, analysisId, source, MAX_INPUT_BYTES);
            ArtifactReference artifact = new CaseArtifactAccess(layout.projectCases(projectId)).describe(
                    caseId, analysisId.value() + "-algorithm-input", "ALGORITHM_INPUT",
                    "application/json", copied);
            PreviousComparison previous = comparePrevious(
                    archive, caseId, analysisId, artifact.sha256());
            archive.registerArtifact(caseId, artifact, clock.instant());

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
                    previous.comparison(), previous.analysisId(), artifact, clock.instant());
            archive.createAlgorithmInputCapture(capture);
            return new ArtifactBackedResult<>(capture, artifact);
        } catch (AlgorithmInputLocationException failure) {
            throw new CaseRunException(failure.code(), "Algorithm input location failed", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Algorithm input archive operation failed", failure);
        }
    }

    private static PreviousComparison comparePrevious(
            CaseArchiveRepository archive, CaseId caseId, AnalysisId analysisId, String currentSha) {
        Optional<AlgorithmInputCapture> previous = archive.findLatestAlgorithmInputCaptureBefore(
                caseId, analysisId);
        if (previous.isEmpty()) {
            return new PreviousComparison(
                    AlgorithmInputComparison.FIRST_CAPTURE, Optional.empty());
        }
        AnalysisId previousId = previous.orElseThrow().analysisId();
        if (currentSha == null) {
            return new PreviousComparison(
                    AlgorithmInputComparison.INCOMPARABLE, Optional.of(previousId));
        }
        try {
            AlgorithmInputCapture verified = archive.requireVerifiedAlgorithmInputCapture(
                    caseId, previousId);
            AlgorithmInputComparison comparison = currentSha.equals(verified.artifact().sha256())
                    ? AlgorithmInputComparison.UNCHANGED : AlgorithmInputComparison.CHANGED;
            return new PreviousComparison(comparison, Optional.of(previousId));
        } catch (WorkspaceException failure) {
            return new PreviousComparison(
                    AlgorithmInputComparison.INCOMPARABLE, Optional.of(previousId));
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

    private record PreviousComparison(
            AlgorithmInputComparison comparison, Optional<AnalysisId> analysisId) { }
}
