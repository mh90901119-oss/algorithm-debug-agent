package org.example.algorithmdebug.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.example.algorithmdebug.contracts.SnapshotCompleteness;
import org.example.algorithmdebug.plan.CodePathPlanCompiler;
import org.example.algorithmdebug.plan.CodePathPlanRequest;
import org.example.algorithmdebug.plan.JdwpPlanCompiler;
import org.example.algorithmdebug.plan.JdwpPlanRequest;
import org.example.algorithmdebug.plan.PlanCompilationException;
import org.example.algorithmdebug.staticanalysis.JavaSourceCallGraphAnalyzer;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisBudget;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisException;
import org.example.algorithmdebug.staticanalysis.StaticAnalysisRequest;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;
import org.example.algorithmdebug.methodpath.MethodPathCollectionException;
import org.example.algorithmdebug.methodpath.TargetClasspathResolver;

/** 编排静态方法目录、CodePath 计划和 JDWP 计划，并追加归档到同一 Case。 */
public final class StaticAnalysisApplicationService {
    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final JavaSourceCallGraphAnalyzer analyzer;
    private final CodePathPlanCompiler compiler;
    private final JdwpPlanCompiler jdwpCompiler;
    private final Clock clock;
    private final AgentExecutionLog executionLog;
    private Optional<Path> mavenExecutable = Optional.empty();
    private Optional<TargetClasspathResolver> classpathResolver = Optional.empty();

    /** 注入确定性仓储、分析器、计划编译器和时钟。 */
    public StaticAnalysisApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaSourceCallGraphAnalyzer analyzer,
            CodePathPlanCompiler compiler,
            Clock clock) {
        this(registrations, mapper, writer, analyzer, compiler, clock, AgentExecutionLog.disabled());
    }

    public StaticAnalysisApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaSourceCallGraphAnalyzer analyzer,
            CodePathPlanCompiler compiler,
            Clock clock,
            AgentExecutionLog executionLog) {
        if (registrations == null || mapper == null || writer == null || analyzer == null
                || compiler == null || clock == null || executionLog == null) {
            throw new IllegalArgumentException("StaticAnalysisApplicationService dependencies must not be null");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.analyzer = analyzer;
        this.compiler = compiler;
        this.jdwpCompiler = new JdwpPlanCompiler();
        this.clock = clock;
        this.executionLog = executionLog;
    }

    /** 注入目标 Maven Classpath 解析器；解析失败时静态分析降级但仍归档不完整目录。 */
    public StaticAnalysisApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            JavaSourceCallGraphAnalyzer analyzer,
            CodePathPlanCompiler compiler,
            Clock clock,
            Optional<Path> mavenExecutable,
            TargetClasspathResolver classpathResolver,
            AgentExecutionLog executionLog) {
        this(registrations, mapper, writer, analyzer, compiler, clock, executionLog);
        if (mavenExecutable == null
                || (mavenExecutable.isPresent() && classpathResolver == null)) {
            throw new IllegalArgumentException("Static analysis classpath dependencies are invalid");
        }
        this.mavenExecutable = mavenExecutable;
        this.classpathResolver = Optional.ofNullable(classpathResolver);
    }

    /** 为已有 Analysis 构建并归档一次静态方法目录，不计算整模块源码指纹。 */
    public ArtifactBackedResult<StaticAnalysisSummary> analyze(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withAnalysis(analysisId);
        executionLog.info(logContext, "StaticAnalysisApplicationService", "STATIC_ANALYSIS_STARTED",
                "STARTED", "Static analysis started");
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            var analysis = archive.requireAnalysis(caseId, analysisId);
            var manifest = archive.requireCase(caseId);
            if (!manifest.projectId().equals(projectId)) {
                throw new CaseRunException("CASE_PROJECT_MISMATCH", "Case does not belong to the specified Project");
            }
            StaticAnalysisRequest request = new StaticAnalysisRequest(
                    Path.of(registration.moduleRoot()), manifest.targetTest(), caseId,
                    analysisId, StaticAnalysisBudget.defaults(), clock.instant());
            MethodCatalog catalog = analyzeWithTargetClasspath(
                    request, Path.of(registration.moduleRoot()),
                    layout.projectCases(projectId).resolve(caseId.value()), logContext);
            Path document = archive.createMethodCatalog(catalog);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    scopedArtifactId(analysisId.value() + "-method-catalog"),
                    "METHOD_CATALOG", "STATIC_");
            archive.registerArtifact(caseId, artifact, clock.instant());
            ArtifactBackedResult<StaticAnalysisSummary> result = new ArtifactBackedResult<>(new StaticAnalysisSummary(
                    catalog.caseId(), catalog.analysisId(),
                    catalog.completeness(), catalog.entries().size(), catalog.edges().size(),
                    catalog.warnings().size()), artifact);
            executionLog.info(logContext, "StaticAnalysisApplicationService", "STATIC_ANALYSIS_COMPLETED",
                    catalog.completeness().name(), "Static analysis completed", Map.of(
                            "methodCount", Integer.toString(catalog.entries().size()),
                            "edgeCount", Integer.toString(catalog.edges().size()),
                            "warningCount", Integer.toString(catalog.warnings().size())));
            return result;
        } catch (StaticAnalysisException failure) {
            throw new CaseRunException(failure.code(), "Static method catalog build failed", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException("STATIC_ARCHIVE_FAILED", "Static method catalog archival or read failed", failure);
        }
    }

    /** 基于已归档目录编译并归档精确 CodePath 采集计划。 */
    public ArtifactBackedResult<CodePathPlanSummary> createCodePathPlan(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId,
            CodePathPlanRequest request) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withAnalysis(analysisId);
        executionLog.info(logContext, "StaticAnalysisApplicationService", "CODEPATH_PLAN_STARTED",
                "STARTED", "CodePath plan compilation started");
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            archive.requireVerifiedAlgorithmInputCapture(caseId, analysisId);
            requireEvidenceLineage(archive, caseId, request.intent());
            CodePathCollectionPlan plan = compiler.compile(
                    archive.requireMethodCatalog(caseId, analysisId), request);
            Path document = archive.createCodePathPlan(plan);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    scopedArtifactId(analysisId.value() + "-codepath-plan-" + plan.planId().value()),
                    "CODEPATH_PLAN", "PLAN_");
            archive.registerArtifact(caseId, artifact, clock.instant());
            executionLog.info(logContext.withPlan(plan.planId().value()),
                    "StaticAnalysisApplicationService", "CODEPATH_PLAN_COMPLETED",
                    "COMPLETED", "CodePath plan was archived", Map.of(
                            "methodCount", Integer.toString(plan.methodSelections().size()),
                            "projectionCount", Integer.toString(plan.methodSelections().stream()
                                    .mapToInt(selection -> selection.projections().size()).sum()),
                            "basedOnEvidenceCount", Integer.toString(
                                    plan.intent().basedOnEvidenceIds().size()),
                            "scopeConfigured", Boolean.toString(plan.scopeMethodKey().isPresent())));
            return new ArtifactBackedResult<>(new CodePathPlanSummary(
                    plan.caseId(), plan.analysisId(), plan.planId(),
                    plan.methodSelections().size()), artifact);
        } catch (PlanCompilationException failure) {
            throw new CaseRunException("PLAN_COMPILATION_FAILED", "CodePath plan compilation failed", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException("PLAN_ARCHIVE_FAILED", "CodePath plan archival or catalog read failed", failure);
        }
    }

    /** 基于同一 Analysis 的 MethodCatalog 编译并归档 JDWP 采集计划。 */
    public ArtifactBackedResult<JdwpPlanSummary> createJdwpPlan(
            Path workspaceRoot, ProjectId projectId, CaseId caseId, AnalysisId analysisId,
            JdwpPlanRequest request) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withAnalysis(analysisId);
        executionLog.info(logContext, "StaticAnalysisApplicationService", "JDWP_PLAN_STARTED",
                "STARTED", "JDWP plan compilation started");
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            archive.requireVerifiedAlgorithmInputCapture(caseId, analysisId);
            requireEvidenceLineage(archive, caseId, request.intent());
            var plan = jdwpCompiler.compile(
                    archive.requireMethodCatalog(caseId, analysisId), request,
                    Path.of(registration.moduleRoot()));
            Path document = archive.createJdwpPlan(plan);
            ArtifactReference artifact = describeArtifact(
                    layout.projectCases(projectId).resolve(caseId.value()), document,
                    scopedArtifactId(analysisId.value() + "-jdwp-plan-" + plan.planId().value()),
                    "JDWP_PLAN", "JDWP_PLAN_");
            archive.registerArtifact(caseId, artifact, clock.instant());
            executionLog.info(logContext.withPlan(plan.planId().value()),
                    "StaticAnalysisApplicationService", "JDWP_PLAN_COMPLETED",
                    "COMPLETED", "JDWP plan was archived", Map.of(
                            "tracepointCount", Integer.toString(plan.tracepoints().size()),
                            "totalObservedBudget", Integer.toString(plan.tracepoints().stream()
                                    .mapToInt(org.example.algorithmdebug.contracts.JdwpTracepointSpec::maxObservedHits).sum()),
                            "totalCapturedBudget", Integer.toString(plan.tracepoints().stream()
                                    .mapToInt(org.example.algorithmdebug.contracts.JdwpTracepointSpec::maxCapturedHits).sum()),
                            "conditionalTracepointCount", Long.toString(plan.tracepoints().stream()
                                    .filter(point -> !point.conditions().isEmpty()).count()),
                            "requestedValuePathCount", Integer.toString(plan.tracepoints().stream()
                                    .mapToInt(point -> point.capture().valuePaths().size()).sum()),
                            "basedOnEvidenceCount", Integer.toString(
                                    plan.intent().basedOnEvidenceIds().size())));
            return new ArtifactBackedResult<>(new JdwpPlanSummary(
                    plan.caseId(), plan.analysisId(), plan.planId(),
                    plan.tracepoints().size(), plan.budget().maxEvents(), plan.budget().maxBytes()),
                    artifact);
        } catch (PlanCompilationException failure) {
            throw new CaseRunException("JDWP_PLAN_COMPILATION_FAILED", "JDWP plan compilation failed", failure);
        } catch (WorkspaceException failure) {
            throw new CaseRunException("JDWP_PLAN_ARCHIVE_FAILED", "JDWP plan archival or catalog read failed", failure);
        }
    }

    private MethodCatalog analyzeWithTargetClasspath(
            StaticAnalysisRequest request,
            Path moduleRoot,
            Path caseDirectory,
            AgentLogContext logContext) {
        if (mavenExecutable.isEmpty() || classpathResolver.isEmpty()) {
            return analyzer.analyze(request);
        }
        try {
            List<Path> entries = classpathResolver.orElseThrow().resolve(
                            mavenExecutable.orElseThrow(), moduleRoot, caseDirectory)
                    .stream().map(Path::of).toList();
            executionLog.info(logContext, "StaticAnalysisApplicationService",
                    "STATIC_CLASSPATH_RESOLVED", "COMPLETED",
                    "Target Maven test classpath was resolved", Map.of(
                            "classpathEntryCount", Integer.toString(entries.size())));
            return analyzer.analyze(request, entries);
        } catch (MethodPathCollectionException failure) {
            executionLog.warn(logContext, "StaticAnalysisApplicationService",
                    "STATIC_CLASSPATH_UNAVAILABLE", "INCOMPLETE",
                    "Target Maven test classpath was unavailable", Map.of(
                            "code", failure.code()));
            return withClasspathWarning(analyzer.analyze(request), failure.code());
        }
    }

    private static MethodCatalog withClasspathWarning(MethodCatalog catalog, String failureCode) {
        List<String> warnings = new ArrayList<>(1_000);
        warnings.add("TEST_CLASSPATH_UNAVAILABLE: " + failureCode);
        catalog.warnings().stream()
                .filter(value -> !value.startsWith("TEST_CLASSPATH_UNAVAILABLE"))
                .limit(999)
                .forEach(warnings::add);
        return new MethodCatalog(
                catalog.schemaVersion(), catalog.caseId(), catalog.analysisId(),
                catalog.targetTest(), catalog.entries(), catalog.edges(), List.copyOf(warnings),
                SnapshotCompleteness.INCOMPLETE, catalog.discoveredMethodCount(),
                catalog.discoveredEdgeCount(), catalog.createdAt());
    }

    private static void requireEvidenceLineage(
            CaseArchiveRepository archive,
            CaseId caseId,
            org.example.algorithmdebug.contracts.InvestigationIntent intent) {
        for (var evidenceId : intent.basedOnEvidenceIds()) {
            try {
                archive.requireEvidenceBundle(caseId, evidenceId);
            } catch (WorkspaceException failure) {
                throw new CaseRunException(
                        "PLAN_EVIDENCE_NOT_FOUND",
                        "Plan references Evidence that is not available in the current Case",
                        failure);
            }
        }
    }

    private ProjectRegistration requireRegistration(WorkspaceLayout layout, ProjectId projectId) {
        return registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "Project is not registered: " + projectId.value()));
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
            throw new CaseRunException(errorPrefix + "ARTIFACT_REFERENCE_FAILED", "Archived artifact path is invalid");
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
            throw new CaseRunException(errorPrefix + "ARTIFACT_REFERENCE_FAILED", "Failed to verify the archived artifact", failure);
        }
    }

    private static String scopedArtifactId(String candidate) {
        if (candidate.length() <= 128 && !candidate.contains("/")
                && !candidate.contains("\\") && !candidate.contains(":")) {
            return candidate;
        }
        try {
            return "artifact-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK is missing SHA-256", failure);
        }
    }
}
