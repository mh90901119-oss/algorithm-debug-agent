package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseArtifactRegistration;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.CollectionExecutionSummary;
import org.example.algorithmdebug.contracts.MethodPathCollectionRecord;
import org.example.algorithmdebug.contracts.CollectionBaselineCheck;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpCollectionRecord;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;
import org.example.algorithmdebug.contracts.RunResultFingerprint;
import org.example.algorithmdebug.contracts.CollectionValidation;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceBundle;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.JdwpSnapshotSummary;
import org.example.algorithmdebug.contracts.MethodPathSummary;
import org.example.algorithmdebug.contracts.NormalizationManifest;
import org.example.algorithmdebug.contracts.SufficiencyEvaluation;
import org.example.algorithmdebug.contracts.SchemaVersions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 原子创建并有界读取外部 Workspace 中的追加式 Case 归档。 */
public final class CaseArchiveRepository {

    private final Path casesRoot;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;

    /**
     * @param casesRoot 已存在的项目 Case 根目录
     * @param mapper 有界 JSON Mapper
     * @param writer 原子 create-new Writer
     */
    public CaseArchiveRepository(
            Path casesRoot,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer) {
        if (casesRoot == null || mapper == null || writer == null) {
            throw new IllegalArgumentException("Case Archive dependencies must not be null");
        }
        this.casesRoot = casesRoot.toAbsolutePath().normalize();
        if (Files.exists(this.casesRoot, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(this.casesRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_PATH_INVALID", "Project Case root does not exist or is not a directory");
        }
        Path parent = this.casesRoot.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_PATH_INVALID", "Project directory does not exist");
        }
        this.mapper = mapper;
        this.writer = writer;
    }

    /** 创建新 Case 目录和不可变身份清单。 */
    public void createCase(CaseManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
        CaseArchiveLayout layout = layout(manifest.caseId());
        try {
            writer.writeNewWithParents(layout.caseDocument(), mapper.writeJson(manifest));
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }


    /** 在已有 Case 下追加一个 Analysis 请求。 */
    public void createAnalysis(AnalysisRequest analysis) {
        AnalysisRequest checked = requireNonNull(analysis, "analysis");
        requireCase(checked.caseId());
        CaseArchiveLayout layout = layout(checked.caseId());
        try {
            writer.writeNewWithParents(
                    layout.analysisDocument(checked.analysisId()), mapper.writeJson(checked));
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 校验 Artifact 当前内容后，按 ID 原子追加注册。 */
    public Path registerArtifact(
            CaseId caseId, ArtifactReference artifact, java.time.Instant registeredAt) {
        requireCase(requireNonNull(caseId, "caseId"));
        ArtifactReference checked = requireNonNull(artifact, "artifact");
        CaseArtifactAccess access = new CaseArtifactAccess(casesRoot);
        Path file = access.requireRegularArtifact(
                caseId, checked.relativePath(), Math.max(1L, checked.sizeBytes()));
        ArtifactReference actual = access.describe(
                caseId, checked.artifactId(), checked.artifactType(), checked.mediaType(), file);
        if (!actual.equals(checked)) {
            throw new WorkspaceException(
                    "CASE_ARTIFACT_INTEGRITY_MISMATCH", "Artifact content verification failed before registration");
        }
        Path registrationPath = layout(caseId).artifactRegistration(checked.artifactId());
        if (Files.isRegularFile(registrationPath, LinkOption.NOFOLLOW_LINKS)) {
            CaseArtifactRegistration existing = requireArtifactRegistration(
                    caseId, checked.artifactId());
            if (existing.artifact().equals(checked)) {
                return registrationPath;
            }
            throw new WorkspaceException(
                    "CASE_ARTIFACT_INTEGRITY_MISMATCH",
                    "The same Artifact ID is already registered with different content");
        }
        CaseArtifactRegistration registration = new CaseArtifactRegistration(
                SchemaVersions.CASE_ARTIFACT_REGISTRATION, caseId, checked,
                requireNonNull(registeredAt, "registeredAt"));
        return createP4Document(registrationPath,
                registration, BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 读取指定 Artifact ID 的不可变注册。 */
    public CaseArtifactRegistration requireArtifactRegistration(
            CaseId caseId, String artifactId) {
        CaseArtifactRegistration value = requireDocument(
                layout(caseId).artifactRegistration(artifactId),
                CaseArtifactRegistration.class, "CASE_ARTIFACT_NOT_REGISTERED");
        if (!caseId.equals(value.caseId())
                || !artifactId.equals(value.artifact().artifactId())) {
            throw identityMismatch("Artifact registration identity does not match its path");
        }
        return value;
    }

    /** 以流式硬上限把目标 UT 的单一算法输入按原文件名原子复制到当前 Case。 */
    public Path copyAlgorithmInput(
            CaseId caseId, AnalysisId analysisId, Path source, long maximumBytes) {
        requireAnalysis(requireNonNull(caseId, "caseId"), requireNonNull(analysisId, "analysisId"));
        if (source == null || maximumBytes < 1) {
            throw new IllegalArgumentException("source and maximumBytes are required");
        }
        CaseArchiveLayout layout = layout(caseId);
        try {
            Path target = layout.caseInputArtifact(source.getFileName().toString());
            writer.writeNewWithParents(
                    target, maximumBytes, output -> Files.copy(source, output));
            return target;
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "ALGORITHM_INPUT_COPY_FAILED", "Unable to copy the algorithm input", failure);
        }
    }

    /** 原子创建当前 Analysis 的输入控制文档；同一 Analysis 不得覆盖。 */
    public Path createAlgorithmInputCapture(
            org.example.algorithmdebug.contracts.AlgorithmInputCapture capture) {
        var checked = requireNonNull(capture, "capture");
        CaseManifest manifest = requireCase(checked.caseId());
        AnalysisRequest analysis = requireAnalysis(checked.caseId(), checked.analysisId());
        if (!manifest.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("Algorithm input identity does not match its Analysis");
        }
        return createP4Document(
                layout(checked.caseId()).analysisInputCapture(checked.analysisId()),
                checked, BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 读取当前 Analysis 的算法输入控制文档，不存在时返回空。 */
    public Optional<org.example.algorithmdebug.contracts.AlgorithmInputCapture>
            findAlgorithmInputCapture(CaseId caseId, AnalysisId analysisId) {
        requireAnalysis(requireNonNull(caseId, "caseId"), requireNonNull(analysisId, "analysisId"));
        Path document = layout(caseId).analysisInputCapture(analysisId);
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        var capture = mapper.readJson(
                document, org.example.algorithmdebug.contracts.AlgorithmInputCapture.class);
        validateAlgorithmInputIdentity(caseId, analysisId, capture);
        return Optional.of(capture);
    }

    /** 读取并校验当前 Analysis 的算法输入控制文档、注册记录和内容 SHA。 */
    public org.example.algorithmdebug.contracts.AlgorithmInputCapture
            requireVerifiedAlgorithmInputCapture(CaseId caseId, AnalysisId analysisId) {
        var capture = findAlgorithmInputCapture(caseId, analysisId).orElseThrow(() ->
                new WorkspaceException(
                        "ANALYSIS_INPUT_NOT_CAPTURED",
                        "Current Analysis has no captured algorithm input"));
        ArtifactReference registered = requireArtifactRegistration(
                caseId, capture.artifact().artifactId()).artifact();
        if (!registered.equals(capture.artifact())) {
            throw new WorkspaceException(
                    "CASE_ARTIFACT_INTEGRITY_MISMATCH",
                    "Algorithm input registration does not match its control document");
        }
        new CaseArtifactAccess(casesRoot).requireVerifiedArtifact(caseId, registered);
        return capture;
    }

    /** 返回同一 Case 中、当前 Analysis 之外最近一次成功写入的输入控制文档。 */
    public Optional<org.example.algorithmdebug.contracts.AlgorithmInputCapture>
            findLatestAlgorithmInputCaptureBefore(CaseId caseId, AnalysisId analysisId) {
        requireAnalysis(requireNonNull(caseId, "caseId"), requireNonNull(analysisId, "analysisId"));
        Path analyses = layout(caseId).analysesRoot();
        if (!Files.isDirectory(analyses, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> entries = Files.list(analyses)) {
            return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.resolve("input/input-analysis.json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> mapper.readJson(
                            path, org.example.algorithmdebug.contracts.AlgorithmInputCapture.class))
                    .filter(capture -> capture.caseId().equals(caseId))
                    .filter(capture -> !capture.analysisId().equals(analysisId))
                    .max(java.util.Comparator
                            .comparing(org.example.algorithmdebug.contracts.AlgorithmInputCapture::capturedAt)
                            .thenComparing(capture -> capture.analysisId().value()));
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException(
                    "ALGORITHM_INPUT_HISTORY_READ_FAILED",
                    "Unable to inspect previous algorithm inputs", failure);
        }
    }

    private static void validateAlgorithmInputIdentity(
            CaseId caseId, AnalysisId analysisId,
            org.example.algorithmdebug.contracts.AlgorithmInputCapture capture) {
        if (!capture.caseId().equals(caseId) || !capture.analysisId().equals(analysisId)) {
            throw identityMismatch("Algorithm input document identity does not match its path");
        }
    }

    /** 为已有 Analysis 原子创建静态方法目录；同一 Analysis 不得覆盖。 */
    public Path createMethodCatalog(MethodCatalog catalog) {
        MethodCatalog checked = requireNonNull(catalog, "catalog");
        CaseManifest manifest = requireCase(checked.caseId());
        AnalysisRequest analysis = requireAnalysis(checked.caseId(), checked.analysisId());
        if (!manifest.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("MethodCatalog and Case/Analysis identity does not match");
        }
        Path document = layout(checked.caseId()).analysisMethodCatalog(checked.analysisId());
        try {
            writer.writeNew(document, BoundedDocumentMapper.MAX_JSON_ARTIFACT_BYTES,
                    output -> mapper.writeJsonArtifact(output, checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 读取指定 Analysis 的静态方法目录。 */
    public MethodCatalog requireMethodCatalog(CaseId caseId, AnalysisId analysisId) {
        Path document = layout(caseId).analysisMethodCatalog(analysisId);
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("METHOD_CATALOG_NOT_FOUND", "The archived Case document does not exist");
        }
        MethodCatalog value;
        try {
            value = mapper.readJsonArtifact(document, MethodCatalog.class);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "The archived MethodCatalog document is invalid", failure);
        }
        if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())) {
            throw identityMismatch("MethodCatalog document identity does not match its path");
        }
        return value;
    }

    /** 为已有 MethodCatalog 原子创建 CodePath 计划；计划 ID 不得覆盖。 */
    public Path createCodePathPlan(CodePathCollectionPlan plan) {
        CodePathCollectionPlan checked = requireNonNull(plan, "plan");
        MethodCatalog catalog = requireMethodCatalog(checked.caseId(), checked.analysisId());
        if (!catalog.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("CodePath plan identity does not match MethodCatalog");
        }
        validatePlanSelectors(catalog, checked);
        Path document = layout(checked.caseId()).planDocument(
                checked.analysisId(), checked.planId());
        try {
            writer.writeNewWithParents(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    private static void validatePlanSelectors(
            MethodCatalog catalog, CodePathCollectionPlan plan) {
        var catalogEntries = new HashMap<String, org.example.algorithmdebug.contracts.MethodCatalogEntry>();
        catalog.entries().forEach(entry -> catalogEntries.put(entry.methodKey(), entry));
        var selectorKeys = new HashSet<String>();
        for (var selection : plan.methodSelections()) {
            var selector = selection.selector();
            if (!selectorKeys.add(selector.methodKey())) {
                throw identityMismatch("CodePath plan contains duplicate selector: " + selector.methodKey());
            }
            var entry = catalogEntries.get(selector.methodKey());
            if (entry == null) {
                throw identityMismatch("CodePath selector does not belong to MethodCatalog: " + selector.methodKey());
            }
            var anchor = entry.sourceAnchor();
            if (!selector.className().equals(anchor.className())
                    || !selector.methodName().equals(anchor.methodName())
                    || !selector.descriptor().equals(anchor.descriptor())) {
                throw identityMismatch("The CodePath selector does not match the MethodCatalog SourceAnchor");
            }
        }
    }

    /** 从指定 Analysis 读取 CodePath 计划。 */
    public CodePathCollectionPlan requireCodePathPlan(
            CaseId caseId, AnalysisId analysisId, PlanId planId) {
        CodePathCollectionPlan value = requireDocument(
                layout(caseId).planDocument(analysisId, planId), CodePathCollectionPlan.class,
                "CODEPATH_PLAN_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())
                || !planId.equals(value.planId())) {
            throw identityMismatch("CodePath plan document identity does not match its path");
        }
        return value;
    }

    /** 在 Case 内按唯一 PlanId 查找计划；跨 Analysis 重名时拒绝歧义。 */
    public CodePathCollectionPlan requireCodePathPlan(CaseId caseId, PlanId planId) {
        CaseArchiveLayout layout = layout(caseId);
        List<Path> matches = childDirectories(layout.analysesRoot()).stream()
                .map(path -> path.resolve("plans").resolve(planId.value() + ".json"))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .toList();
        if (matches.size() != 1) {
            throw new WorkspaceException(
                    "CODEPATH_PLAN_NOT_FOUND", "The CodePath plan does not exist or its PlanId is not unique across Analyses");
        }
        String analysisSegment = matches.getFirst().getParent().getParent().getFileName().toString();
        AnalysisId analysisId;
        try {
            analysisId = new AnalysisId(analysisSegment);
        } catch (IllegalArgumentException failure) {
            throw new WorkspaceException("CASE_DOCUMENT_INVALID", "CodePath plan path identity is invalid", failure);
        }
        return requireCodePathPlan(caseId, analysisId, planId);
    }

    /** 为已有 MethodCatalog 原子创建 JDWP 计划；计划 ID 不得覆盖。 */
    public Path createJdwpPlan(JdwpCollectionPlan plan) {
        JdwpCollectionPlan checked = requireNonNull(plan, "plan");
        MethodCatalog catalog = requireMethodCatalog(checked.caseId(), checked.analysisId());
        if (!catalog.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("JDWP plan identity does not match MethodCatalog");
        }
        var entries = new HashMap<String, org.example.algorithmdebug.contracts.MethodCatalogEntry>();
        catalog.entries().forEach(entry -> entries.put(entry.methodKey(), entry));
        for (var point : checked.tracepoints()) {
            var entry = entries.get(point.methodKey());
            if (entry == null || !entry.sourceAnchor().equals(point.sourceAnchor())) {
                throw identityMismatch("JDWP tracepoint is outside MethodCatalog or its source anchor does not match");
            }
        }
        Path document = layout(checked.caseId()).planDocument(
                checked.analysisId(), checked.planId());
        try {
            writer.writeNewWithParents(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 从指定 Analysis 读取 JDWP 计划。 */
    public JdwpCollectionPlan requireJdwpPlan(
            CaseId caseId, AnalysisId analysisId, PlanId planId) {
        JdwpCollectionPlan value = requireDocument(
                layout(caseId).planDocument(analysisId, planId), JdwpCollectionPlan.class,
                "JDWP_PLAN_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())
                || !planId.equals(value.planId())) {
            throw identityMismatch("JDWP plan document identity does not match its path");
        }
        return value;
    }

    /** 在 Case 内按唯一 PlanId 查找 JDWP 计划；跨 Analysis 重名时拒绝歧义。 */
    public JdwpCollectionPlan requireJdwpPlan(CaseId caseId, PlanId planId) {
        CaseArchiveLayout layout = layout(caseId);
        List<Path> matches = childDirectories(layout.analysesRoot()).stream()
                .map(path -> path.resolve("plans").resolve(planId.value() + ".json"))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .toList();
        if (matches.size() != 1) {
            throw new WorkspaceException(
                    "JDWP_PLAN_NOT_FOUND", "The JDWP plan does not exist or its PlanId is not unique across Analyses");
        }
        String analysisSegment = matches.getFirst().getParent().getParent().getFileName().toString();
        try {
            return requireJdwpPlan(caseId, new AnalysisId(analysisSegment), planId);
        } catch (IllegalArgumentException failure) {
            throw new WorkspaceException("CASE_DOCUMENT_INVALID", "JDWP plan path identity is invalid", failure);
        }
    }

    /**
     * 在外部 Collector 启动前只创建 Collection 根目录和请求文档；其余目录由实际生产者按需创建。
     *
     * @return 已创建的 Collection 绝对目录
     */
    public Path startMethodPathCollection(MethodPathCollectionRecord record) {
        MethodPathCollectionRecord checked = requireNonNull(record, "record");
        CodePathCollectionPlan plan = requireCodePathPlan(
                checked.caseId(), checked.analysisId(), checked.planId());
        if (!plan.analysisId().equals(checked.analysisId())
                || !plan.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("Collection request identity does not match the CodePath plan");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        Path root = layout.collectionRoot(checked.collectionId());
        try {
            writer.writeNewWithParents(
                    layout.collectionRequest(checked.collectionId()), mapper.writeJson(checked));
            return root;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 在外部双进程启动前只追加 JDWP Collection 请求，其他目录由实际生产者按需创建。 */
    public Path startJdwpCollection(JdwpCollectionRecord record) {
        JdwpCollectionRecord checked = requireNonNull(record, "record");
        JdwpCollectionPlan plan = requireJdwpPlan(
                checked.caseId(), checked.analysisId(), checked.planId());
        if (!plan.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("JDWP Collection request identity does not match the plan");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        Path root = layout.collectionRoot(checked.collectionId());
        try {
            writer.writeNewWithParents(
                    layout.collectionRequest(checked.collectionId()), mapper.writeJson(checked));
            return root;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 读取指定 JDWP Collection 的启动请求。 */
    public JdwpCollectionRecord requireJdwpCollection(
            CaseId caseId, CollectionId collectionId) {
        JdwpCollectionRecord value = requireDocument(
                layout(caseId).collectionRequest(collectionId), JdwpCollectionRecord.class,
                "COLLECTION_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !collectionId.equals(value.collectionId())) {
            throw identityMismatch("JDWP Collection request document identity does not match its path");
        }
        return value;
    }

    /** 读取指定 Collection 的启动请求。 */
    public MethodPathCollectionRecord requireMethodPathCollection(
            CaseId caseId, CollectionId collectionId) {
        MethodPathCollectionRecord value = requireDocument(
                layout(caseId).collectionRequest(collectionId), MethodPathCollectionRecord.class,
                "COLLECTION_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !collectionId.equals(value.collectionId())) {
            throw identityMismatch("Collection request document identity does not match its path");
        }
        return value;
    }

    /** 为已启动 Collection 原子追加 Baseline 检查。 */
    public Path createCollectionBaselineCheck(CollectionBaselineCheck check) {
        CollectionBaselineCheck checked = requireNonNull(check, "check");
        MethodPathCollectionRecord request = requireMethodPathCollection(
                checked.caseId(), checked.collectionId());
        if (!request.analysisId().equals(checked.analysisId())
                || !request.runId().equals(checked.runId())) {
            throw identityMismatch("Baseline check does not match Collection request identity");
        }
        Path document = layout(checked.caseId()).collectionBaselineCheck(checked.collectionId());
        try {
            writer.writeNewWithParents(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 原子追加一次 Collection 的可恢复 Tool 摘要。 */
    public Path createCollectionExecutionSummary(CollectionExecutionSummary summary) {
        CollectionExecutionSummary checked = requireNonNull(summary, "summary");
        requireCollectionSummaryIdentity(checked);
        return createP4Document(
                layout(checked.caseId()).collectionSummary(checked.collectionId()),
                checked, BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 读取指定 Collection 的完成摘要。 */
    public CollectionExecutionSummary requireCollectionExecutionSummary(
            CaseId caseId, CollectionId collectionId) {
        CollectionExecutionSummary value = requireDocument(
                layout(caseId).collectionSummary(collectionId),
                CollectionExecutionSummary.class, "COLLECTION_SUMMARY_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !collectionId.equals(value.collectionId())) {
            throw identityMismatch("CollectionExecutionSummary document identity does not match its path");
        }
        return value;
    }

    /** 为已启动 JDWP Collection 原子追加 Baseline 检查。 */
    public Path createJdwpCollectionBaselineCheck(CollectionBaselineCheck check) {
        CollectionBaselineCheck checked = requireNonNull(check, "check");
        JdwpCollectionRecord request = requireJdwpCollection(
                checked.caseId(), checked.collectionId());
        if (!request.analysisId().equals(checked.analysisId())
                || !request.runId().equals(checked.runId())) {
            throw identityMismatch("Baseline check does not match JDWP Collection request identity");
        }
        Path document = layout(checked.caseId()).collectionBaselineCheck(checked.collectionId());
        try {
            writer.writeNewWithParents(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 在归一化开始前创建 Evidence 目录和不可变构建请求。 */
    public Path createEvidenceRequest(EvidenceBuildRequest request) {
        EvidenceBuildRequest checked = requireNonNull(request, "request");
        requireCase(checked.caseId());
        AnalysisRequest analysis = requireAnalysis(checked.caseId(), checked.analysisId());
        RunRequest run = requireRunRequest(checked.caseId(), checked.runId());
        if (!run.analysisId().equals(analysis.analysisId())) {
            throw identityMismatch("Evidence base Run must belong to the request Analysis");
        }
        if (findRunOutcome(checked.caseId(), checked.runId()).isEmpty()) {
            throw identityMismatch("Evidence request may only reference a completed Run");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        Path root = layout.evidenceRoot(checked.evidenceId());
        try {
            Path document = layout.evidenceBuildRequest(checked.evidenceId());
            writer.writeNewWithParents(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 读取不可变 Evidence 构建请求。 */
    public EvidenceBuildRequest requireEvidenceRequest(CaseId caseId, EvidenceId evidenceId) {
        EvidenceBuildRequest value = requireDocument(
                layout(caseId).evidenceBuildRequest(evidenceId),
                EvidenceBuildRequest.class, "EVIDENCE_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !evidenceId.equals(value.evidenceId())) {
            throw identityMismatch("The Evidence request identity does not match its path");
        }
        return value;
    }

    /** 原子追加一次归一化清单。 */
    public Path createNormalizationManifest(NormalizationManifest manifest) {
        NormalizationManifest checked = requireNonNull(manifest, "manifest");
        requireEvidenceCollectionIdentity(
                checked.caseId(), checked.analysisId(),
                checked.runId(), checked.planId(), checked.collectionId(),
                checked.evidenceId(), checked.collectorType());
        return createP4Document(layout(checked.caseId()).normalizationManifest(
                checked.collectionId(), checked.evidenceId()), checked,
                BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 原子追加一次 CodePath 方法路径摘要。 */
    public Path createMethodPathSummary(MethodPathSummary summary) {
        MethodPathSummary checked = requireNonNull(summary, "summary");
        requireEvidenceCollectionIdentity(
                checked.caseId(), checked.analysisId(),
                checked.runId(), checked.planId(), checked.collectionId(),
                checked.evidenceId(), "CODEPATH");
        return createP4Document(layout(checked.caseId()).methodPathSummary(
                checked.collectionId(), checked.evidenceId()), checked,
                org.example.algorithmdebug.contracts.NormalizationBudget.MAX_SUMMARY_BYTES);
    }

    /** 原子追加一次 JDWP 快照摘要。 */
    public Path createJdwpSnapshotSummary(JdwpSnapshotSummary summary) {
        JdwpSnapshotSummary checked = requireNonNull(summary, "summary");
        requireEvidenceCollectionIdentity(
                checked.caseId(), checked.analysisId(),
                checked.runId(), checked.planId(), checked.collectionId(),
                checked.evidenceId(), "JDWP");
        return createP4Document(layout(checked.caseId()).jdwpSnapshotSummary(
                checked.collectionId(), checked.evidenceId()), checked,
                org.example.algorithmdebug.contracts.NormalizationBudget.MAX_SUMMARY_BYTES);
    }

    /** 原子追加一次 Collection Evidence 校验。 */
    public Path createCollectionValidation(CollectionValidation validation) {
        CollectionValidation checked = requireNonNull(validation, "validation");
        requireEvidenceCollectionIdentity(
                checked.caseId(), checked.analysisId(),
                checked.runId(), checked.planId(), checked.collectionId(),
                checked.evidenceId(), checked.collectorType());
        return createP4Document(layout(checked.caseId()).collectionValidation(
                checked.collectionId(), checked.evidenceId()), checked,
                BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 原子追加面向模型的 Evidence Bundle。 */
    public Path createEvidenceBundle(EvidenceBundle bundle) {
        EvidenceBundle checked = requireNonNull(bundle, "bundle");
        requireEvidenceIdentity(checked.caseId(), checked.analysisId(), checked.evidenceId());
        return createP4Document(
                layout(checked.caseId()).evidenceBundle(checked.evidenceId()), checked,
                BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 读取指定 Evidence Bundle。 */
    public EvidenceBundle requireEvidenceBundle(CaseId caseId, EvidenceId evidenceId) {
        EvidenceBundle value = requireDocument(
                layout(caseId).evidenceBundle(evidenceId),
                EvidenceBundle.class, "EVIDENCE_BUNDLE_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !evidenceId.equals(value.evidenceId())) {
            throw identityMismatch("Evidence Bundle identity does not match its path");
        }
        return value;
    }

    /** 原子追加请求维度的充分性评估。 */
    public Path createSufficiencyEvaluation(SufficiencyEvaluation evaluation) {
        SufficiencyEvaluation checked = requireNonNull(evaluation, "evaluation");
        requireEvidenceIdentity(checked.caseId(), checked.analysisId(), checked.evidenceId());
        return createP4Document(layout(checked.caseId()).sufficiencyEvaluation(
                checked.evidenceId()), checked, BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 在启动外部 Maven 进程前创建 Run 目录、raw 目录和 RunRequest。 */
    public void startRun(RunRequest request) {
        RunRequest checked = requireNonNull(request, "request");
        CaseManifest manifest = requireCase(checked.caseId());
        AnalysisRequest analysis = requireAnalysis(checked.caseId(), checked.analysisId());
        if (!manifest.targetTest().equals(checked.targetTest())) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_IDENTITY_MISMATCH", "RunRequest target UT does not match Case");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        try {
            writer.writeNewWithParents(layout.runRequest(checked.runId()), mapper.writeJson(checked));
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 为已启动 Run 原子创建最终 RunOutcome；不得覆盖已有结果。 */
    public void completeRun(RunOutcomeSummary outcome) {
        RunOutcomeSummary checked = requireNonNull(outcome, "outcome");
        RunRequest request = requireRunRequest(checked.caseId(), checked.runId());
        if (!request.analysisId().equals(checked.analysisId())) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_IDENTITY_MISMATCH", "RunOutcome ownership does not match RunRequest");
        }
        try {
            writer.writeNew(layout(checked.caseId()).runOutcome(checked.runId()), mapper.writeJson(checked));
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /**
     * 为已启动 Run 原子创建确定性结果指纹。
     *
     * @return 已创建文档的绝对路径
     */
    public Path createRunResultFingerprint(RunResultFingerprint fingerprint) {
        RunResultFingerprint checked = requireNonNull(fingerprint, "fingerprint");
        validateFingerprintRunIdentity(checked);
        Path document = layout(checked.caseId()).runResultFingerprint(checked.runId());
        try {
            writer.writeNew(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }




    /** 读取 Case 身份；不存在或损坏时返回结构化错误。 */
    public CaseManifest requireCase(CaseId caseId) {
        return requireDocument(layout(caseId).caseDocument(), CaseManifest.class, "CASE_NOT_FOUND");
    }


    /** 读取指定 Analysis。 */
    public AnalysisRequest requireAnalysis(CaseId caseId, AnalysisId analysisId) {
        AnalysisRequest value = requireDocument(
                layout(caseId).analysisDocument(analysisId), AnalysisRequest.class, "ANALYSIS_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())) {
            throw identityMismatch("Analysis document identity does not match its path");
        }
        return value;
    }

    /** 读取指定 RunRequest。 */
    public RunRequest requireRunRequest(CaseId caseId, RunId runId) {
        RunRequest value = requireDocument(
                layout(caseId).runRequest(runId), RunRequest.class, "RUN_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !runId.equals(value.runId())) {
            throw identityMismatch("RunRequest document identity does not match its path");
        }
        return value;
    }

    /** 查找 RunOutcome；尚未收尾时返回空。 */
    public Optional<RunOutcomeSummary> findRunOutcome(CaseId caseId, RunId runId) {
        Path document = layout(caseId).runOutcome(runId);
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        RunOutcomeSummary value = mapper.readJson(document, RunOutcomeSummary.class);
        if (!caseId.equals(value.caseId()) || !runId.equals(value.runId())) {
            throw identityMismatch("RunOutcome document identity does not match its path");
        }
        return Optional.of(value);
    }

    /** ???? Analysis ???????????? UT ??? */
    public Optional<RunOutcomeSummary> findLatestCompletedRun(
            CaseId caseId, AnalysisId analysisId) {
        requireAnalysis(caseId, analysisId);
        return childDirectories(layout(caseId).runsRoot()).stream()
                .map(path -> new RunId(path.getFileName().toString()))
                .filter(runId -> Files.isRegularFile(
                        layout(caseId).runRequest(runId), LinkOption.NOFOLLOW_LINKS))
                .map(runId -> requireRunRequest(caseId, runId))
                .filter(request -> request.analysisId().equals(analysisId))
                .filter(request -> findRunOutcome(caseId, request.runId()).isPresent())
                .sorted(Comparator.comparing(RunRequest::createdAt)
                        .thenComparing(value -> value.runId().value()).reversed())
                .map(request -> findRunOutcome(caseId, request.runId()).orElseThrow())
                .findFirst();
    }

    /** 查找同一 Analysis 最近归档的普通 UT 失败指纹。 */
    public Optional<RunResultFingerprint> findLatestRunResultFingerprint(
            CaseId caseId, AnalysisId analysisId) {
        requireAnalysis(caseId, analysisId);
        return childDirectories(layout(caseId).runsRoot()).stream()
                .map(path -> new RunId(path.getFileName().toString()))
                .filter(runId -> Files.isRegularFile(
                        layout(caseId).runRequest(runId), LinkOption.NOFOLLOW_LINKS))
                .map(runId -> requireRunRequest(caseId, runId))
                .filter(request -> request.analysisId().equals(analysisId))
                .filter(request -> Files.isRegularFile(
                        layout(caseId).runResultFingerprint(request.runId()),
                        LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(RunRequest::createdAt)
                        .thenComparing(value -> value.runId().value()).reversed())
                .map(request -> {
                    RunResultFingerprint value = readFingerprint(
                            layout(caseId).runResultFingerprint(request.runId()));
                    validateFingerprintPathIdentity(value, caseId, analysisId);
                    validateFingerprintRunIdentity(value);
                    return value;
                })
                .findFirst();
    }
    /** @return 指定 Run 中可写入原始产物的已创建目录 */
    public Path runRawDirectory(CaseId caseId, RunId runId) {
        requireRunRequest(caseId, runId);
        Path raw = layout(caseId).runRaw(runId);
        try {
            Files.createDirectories(raw);
            return raw;
        } catch (IOException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    CaseArchiveLayout layout(CaseId caseId) {
        return CaseArchiveLayout.of(casesRoot, caseId);
    }

    /** @return 指定 Case 的本地归档根目录；仅供同一控制面的确定性产物归档使用 */
    public Path caseRoot(CaseId caseId) {
        requireNonNull(caseId, "caseId");
        return layout(caseId).caseRoot();
    }

    BoundedDocumentMapper mapper() {
        return mapper;
    }

    Path casesRoot() {
        return casesRoot;
    }

    List<Path> childDirectories(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted().toList();
        } catch (IOException | SecurityException failure) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_READ_FAILED", "Failed to enumerate Case subdirectories", failure);
        }
    }

    private void createChildDocument(Path document, Object value) {
        try {
            writer.writeNewWithParents(document, mapper.writeJson(value));
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    private Path createP4Document(Path document, Object value, long maximumBytes) {
        try {
            writer.writeNewWithParents(document, maximumBytes,
                    output -> mapper.writeJsonArtifact(output, value));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    private void requireEvidenceCollectionIdentity(
            CaseId caseId,
            AnalysisId analysisId,
            RunId runId,
            PlanId planId,
            CollectionId collectionId,
            EvidenceId evidenceId,
            String collectorType) {
        EvidenceBuildRequest request = requireEvidenceRequest(caseId, evidenceId);
        boolean currentEvidence = request.collectionIds().contains(collectionId);
        boolean comparisonEvidence = request.comparisonCollectionIds().contains(collectionId);
        if (!currentEvidence && !comparisonEvidence) {
            throw identityMismatch("Derived artifact Collection is not selected by the Evidence request");
        }
        if (currentEvidence && !request.analysisId().equals(analysisId)) {
            throw identityMismatch("Current Evidence Collection must belong to the request Analysis");
        }
        boolean matched;
        if ("CODEPATH".equals(collectorType)) {
            MethodPathCollectionRecord collection = requireMethodPathCollection(caseId, collectionId);
            matched = collection.analysisId().equals(analysisId)
                    && collection.runId().equals(runId)
                    && collection.planId().equals(planId);
        } else if ("JDWP".equals(collectorType)) {
            JdwpCollectionRecord collection = requireJdwpCollection(caseId, collectionId);
            matched = collection.analysisId().equals(analysisId)
                    && collection.runId().equals(runId)
                    && collection.planId().equals(planId);
        } else {
            throw identityMismatch("Unsupported Collector type");
        }
        if (!matched) {
            throw identityMismatch("Derived artifact does not match the source Collection Analysis/Run/Plan");
        }
    }
    private void requireCollectionSummaryIdentity(CollectionExecutionSummary summary) {
        boolean matched;
        try {
            MethodPathCollectionRecord request = requireMethodPathCollection(
                    summary.caseId(), summary.collectionId());
            matched = request.analysisId().equals(summary.analysisId())
                    && request.runId().equals(summary.runId())
                    && request.planId().equals(summary.planId());
        } catch (WorkspaceException codePathFailure) {
            JdwpCollectionRecord request = requireJdwpCollection(
                    summary.caseId(), summary.collectionId());
            matched = request.analysisId().equals(summary.analysisId())
                    && request.runId().equals(summary.runId())
                    && request.planId().equals(summary.planId());
        }
        if (!matched) {
            throw identityMismatch("CollectionExecutionSummary does not match launch request identity");
        }
    }
    private void requireEvidenceIdentity(
            CaseId caseId,
            AnalysisId analysisId,
            EvidenceId evidenceId) {
        EvidenceBuildRequest request = requireEvidenceRequest(caseId, evidenceId);
        if (!request.analysisId().equals(analysisId)) {
            throw identityMismatch("Evidence artifact Analysis does not match build request");
        }
    }
    private <T> T requireDocument(Path document, Class<T> type, String missingCode) {
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(missingCode, "The archived Case document does not exist");
        }
        try {
            return mapper.readJson(document, type);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "The archived Case document is invalid", failure);
        }
    }

    private RunResultFingerprint readFingerprint(Path document) {
        try {
            return mapper.readJson(document, RunResultFingerprint.class);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "RunResultFingerprint document is invalid", failure);
        }
    }


    private void validateFingerprintRunIdentity(RunResultFingerprint fingerprint) {
        RunRequest request = requireRunRequest(fingerprint.caseId(), fingerprint.runId());
        if (!request.analysisId().equals(fingerprint.analysisId())) {
            throw identityMismatch("RunResultFingerprint Analysis does not match RunRequest");
        }
    }
    private static void validateFingerprintPathIdentity(
            RunResultFingerprint fingerprint,
            CaseId caseId,
            AnalysisId analysisId) {
        if (!caseId.equals(fingerprint.caseId())
                || !analysisId.equals(fingerprint.analysisId())) {
            throw identityMismatch("RunResultFingerprint document identity does not match its Analysis");
        }
    }

    private static WorkspaceException identityMismatch(String message) {
        return new WorkspaceException("CASE_ARCHIVE_IDENTITY_MISMATCH", message);
    }

    private static WorkspaceException archiveWriteFailure(Throwable failure) {
        return new WorkspaceException(
                "CASE_ARCHIVE_WRITE_FAILED", "Failed to append safely to the archived Case document", failure);
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
