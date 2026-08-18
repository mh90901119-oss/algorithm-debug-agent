package org.example.algorithmdebug.core;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.ContextSnapshotBuilder;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.SourceSnapshot;
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.plan.PlanCompilationException;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisBudget;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisRequest;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisException;

/** 编排静态方法目录与 CodePath 计划，并把两者追加归档到同一 Case。 */
public final class StaticAnalysisApplicationService {

    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final JavaSourceCallGraphAnalyzer analyzer;
    private final CodePathPlanCompiler compiler;
    private final SourceSnapshotReader sourceSnapshots;
    private final Clock clock;

    /** 注入确定性仓储、分析器、计划编译器和时钟。 */
    public StaticAnalysisApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaSourceCallGraphAnalyzer analyzer,
            CodePathPlanCompiler compiler,
            Clock clock) {
        this(registrations, mapper, writer, analyzer, compiler, clock,
                new ContextSnapshotBuilder()::captureSourceSnapshot);
    }

    /** 注入可替换的源码摘要读取端口，用于验证分析前后源码身份。 */
    public StaticAnalysisApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaSourceCallGraphAnalyzer analyzer,
            CodePathPlanCompiler compiler,
            Clock clock,
            SourceSnapshotReader sourceSnapshots) {
        if (registrations == null || mapper == null || writer == null || analyzer == null
                || compiler == null || clock == null || sourceSnapshots == null) {
            throw new IllegalArgumentException("StaticAnalysisApplicationService 依赖不能为空");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.analyzer = analyzer;
        this.compiler = compiler;
        this.clock = clock;
        this.sourceSnapshots = sourceSnapshots;
    }

    /** 为已有 Analysis 构建并归档一次静态方法目录。 */
    public ArtifactBackedResult<StaticAnalysisSummary> analyze(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId) {
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            var analysis = archive.requireAnalysis(caseId, analysisId);
            var context = archive.requireContext(caseId, analysis.contextId());
            if (!context.projectId().equals(projectId)) {
                throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case 不属于指定 Project");
            }
            Path moduleRoot = Path.of(registration.moduleRoot());
            SourceSnapshot expectedSource = context.sourceSnapshot();
            SourceSnapshot before = sourceSnapshots.capture(moduleRoot);
            requireMatchingSource(expectedSource, before, "分析开始前");
            MethodCatalog catalog = analyzer.analyze(new StaticAnalysisRequest(
                    moduleRoot, context.targetTest(), caseId,
                    context.contextId(), analysisId, expectedSource.sha256(),
                    StaticAnalysisBudget.defaults(), clock.instant()));
            SourceSnapshot after = sourceSnapshots.capture(moduleRoot);
            requireMatchingSource(expectedSource, after, "分析完成后");
            if (!before.equals(after)) {
                throw new CaseRunException(
                        "STATIC_SOURCE_DRIFT", "静态分析期间源码摘要发生变化");
            }
            Path document = archive.createMethodCatalog(catalog);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    analysisId.value(), "METHOD_CATALOG", "STATIC_");
            return new ArtifactBackedResult<>(new StaticAnalysisSummary(
                    catalog.caseId(), catalog.contextId(), catalog.analysisId(),
                    catalog.completeness(), catalog.entries().size(), catalog.edges().size(),
                    catalog.warnings().size()), artifact);
        } catch (StaticAnalysisException failure) {
            throw new CaseRunException(
                    "STATIC_ANALYSIS_FAILED", "静态方法目录构建失败", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(
                    "STATIC_ARCHIVE_FAILED", "静态方法目录归档或读取失败", failure);
        }
    }

    private static void requireMatchingSource(
            SourceSnapshot expected, SourceSnapshot observed, String phase) {
        if (!expected.equals(observed)) {
            throw new CaseRunException(
                    "STATIC_SOURCE_DRIFT", phase + "的源码摘要与 Context 不一致");
        }
    }

    /** 基于已归档目录编译并归档 CodePath 采集计划。 */
    public ArtifactBackedResult<CodePathPlanSummary> createCodePathPlan(
            Path workspaceRoot,
            ProjectId projectId,
            CaseId caseId,
            AnalysisId analysisId,
            CodePathPlanRequest request) {
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            MethodCatalog catalog = archive.requireMethodCatalog(caseId, analysisId);
            CodePathCollectionPlan plan = compiler.compile(catalog, request);
            Path document = archive.createCodePathPlan(plan);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    plan.planId().value(), "CODEPATH_PLAN", "PLAN_");
            return new ArtifactBackedResult<>(new CodePathPlanSummary(
                    plan.caseId(), plan.contextId(), plan.analysisId(), plan.planId(),
                    plan.selectors().size(), plan.packagePrefixes(), plan.estimatedPackageEvents()),
                    artifact);
        } catch (PlanCompilationException failure) {
            throw new CaseRunException(
                    "PLAN_COMPILATION_FAILED", "CodePath 计划编译失败", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(
                    "PLAN_ARCHIVE_FAILED", "CodePath 计划归档或目录读取失败", failure);
        }
    }

    private ProjectRegistration requireRegistration(WorkspaceLayout layout, ProjectId projectId) {
        return registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "项目尚未登记: " + projectId.value()));
    }

    private CaseArchiveRepository archive(WorkspaceLayout layout, ProjectId projectId) {
        return new CaseArchiveRepository(layout.projectCases(projectId), mapper, writer);
    }

    private static ArtifactReference describeArtifact(
            Path caseRoot,
            Path document,
            String artifactId,
            String artifactType,
            String errorPrefix) {
        Path normalizedRoot = caseRoot.toAbsolutePath().normalize();
        Path normalizedDocument = document.toAbsolutePath().normalize();
        if (!normalizedDocument.startsWith(normalizedRoot)
                || !Files.isRegularFile(normalizedDocument, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedDocument)) {
            throw new CaseRunException(
                    errorPrefix + "ARTIFACT_REFERENCE_FAILED", "归档产物路径无效");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(normalizedDocument)) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            String relativePath = normalizedRoot.relativize(normalizedDocument)
                    .toString().replace('\\', '/');
            return new ArtifactReference(
                    artifactId, artifactType, relativePath, "application/json",
                    HexFormat.of().formatHex(digest.digest()), Files.size(normalizedDocument));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new CaseRunException(
                    errorPrefix + "ARTIFACT_REFERENCE_FAILED", "无法校验归档产物", failure);
        }
    }
}
