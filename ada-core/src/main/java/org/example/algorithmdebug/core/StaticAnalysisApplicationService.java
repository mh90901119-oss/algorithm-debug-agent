package org.example.algorithmdebug.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
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
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.plan.JdwpPlanCompiler;
import org.example.algorithmdebug.plan.JdwpPlanRequest;
import org.example.algorithmdebug.plan.PlanCompilationException;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisBudget;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisException;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisRequest;

/** 编排静态方法目录、CodePath 计划和 JDWP 计划，并追加归档到同一 Case。 */
public final class StaticAnalysisApplicationService {
    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final JavaSourceCallGraphAnalyzer analyzer;
    private final CodePathPlanCompiler compiler;
    private final JdwpPlanCompiler jdwpCompiler;
    private final Clock clock;

    /** 注入确定性仓储、分析器、计划编译器和时钟。 */
    public StaticAnalysisApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaSourceCallGraphAnalyzer analyzer,
            CodePathPlanCompiler compiler,
            Clock clock) {
        if (registrations == null || mapper == null || writer == null || analyzer == null
                || compiler == null || clock == null) {
            throw new IllegalArgumentException("StaticAnalysisApplicationService 依赖不能为空");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.analyzer = analyzer;
        this.compiler = compiler;
        this.jdwpCompiler = new JdwpPlanCompiler();
        this.clock = clock;
    }

    /** 为已有 Analysis 构建并归档一次静态方法目录，不计算整模块源码指纹。 */
    public ArtifactBackedResult<StaticAnalysisSummary> analyze(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId) {
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            var analysis = archive.requireAnalysis(caseId, analysisId);
            var context = archive.requireContext(caseId, analysis.contextId());
            var manifest = archive.requireCase(caseId);
            if (!manifest.projectId().equals(projectId)) {
                throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case 不属于指定 Project");
            }
            MethodCatalog catalog = analyzer.analyze(new StaticAnalysisRequest(
                    Path.of(registration.moduleRoot()), manifest.targetTest(), caseId,
                    context.contextId(), analysisId, StaticAnalysisBudget.defaults(), clock.instant()));
            Path document = archive.createMethodCatalog(catalog);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    analysisId.value(), "METHOD_CATALOG", "STATIC_");
            return new ArtifactBackedResult<>(new StaticAnalysisSummary(
                    catalog.caseId(), catalog.contextId(), catalog.analysisId(),
                    catalog.completeness(), catalog.entries().size(), catalog.edges().size(),
                    catalog.warnings().size()), artifact);
        } catch (StaticAnalysisException failure) {
            throw new CaseRunException("STATIC_ANALYSIS_FAILED", "静态方法目录构建失败", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException("STATIC_ARCHIVE_FAILED", "静态方法目录归档或读取失败", failure);
        }
    }

    /** 基于已归档目录编译并归档精确 CodePath 采集计划。 */
    public ArtifactBackedResult<CodePathPlanSummary> createCodePathPlan(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId,
            CodePathPlanRequest request) {
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            CodePathCollectionPlan plan = compiler.compile(
                    archive.requireMethodCatalog(caseId, analysisId), request);
            Path document = archive.createCodePathPlan(plan);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    plan.planId().value(), "CODEPATH_PLAN", "PLAN_");
            return new ArtifactBackedResult<>(new CodePathPlanSummary(
                    plan.caseId(), plan.contextId(), plan.analysisId(), plan.planId(),
                    plan.selectors().size()), artifact);
        } catch (PlanCompilationException failure) {
            throw new CaseRunException("PLAN_COMPILATION_FAILED", "CodePath 计划编译失败", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException("PLAN_ARCHIVE_FAILED", "CodePath 计划归档或目录读取失败", failure);
        }
    }

    /** 基于同一 Analysis 的 MethodCatalog 编译并归档 JDWP 采集计划。 */
    public ArtifactBackedResult<JdwpPlanSummary> createJdwpPlan(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId,
            JdwpPlanRequest request) {
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            var plan = jdwpCompiler.compile(
                    archive.requireMethodCatalog(caseId, analysisId), request,
                    Path.of(registration.moduleRoot()));
            Path document = archive.createJdwpPlan(plan);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    plan.planId().value(), "JDWP_PLAN", "JDWP_PLAN_");
            return new ArtifactBackedResult<>(new JdwpPlanSummary(
                    plan.caseId(), plan.contextId(), plan.analysisId(), plan.planId(),
                    plan.tracepoints().size(), plan.budget().maxEvents(), plan.budget().maxBytes()),
                    artifact);
        } catch (PlanCompilationException failure) {
            throw new CaseRunException("JDWP_PLAN_COMPILATION_FAILED", "JDWP 计划编译失败", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException("JDWP_PLAN_ARCHIVE_FAILED", "JDWP 计划归档或目录读取失败", failure);
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
            Path caseRoot, Path document, String artifactId, String artifactType, String errorPrefix) {
        Path normalizedRoot = caseRoot.toAbsolutePath().normalize();
        Path normalizedDocument = document.toAbsolutePath().normalize();
        if (!normalizedDocument.startsWith(normalizedRoot)
                || !Files.isRegularFile(normalizedDocument, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedDocument)) {
            throw new CaseRunException(errorPrefix + "ARTIFACT_REFERENCE_FAILED", "归档产物路径无效");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(normalizedDocument)) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            String relativePath = normalizedRoot.relativize(normalizedDocument)
                    .toString().replace('\\', '/');
            return new ArtifactReference(
                    artifactId, artifactType, relativePath, "application/json",
                    HexFormat.of().formatHex(digest.digest()), Files.size(normalizedDocument));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new CaseRunException(errorPrefix + "ARTIFACT_REFERENCE_FAILED", "无法校验归档产物", failure);
        }
    }
}
