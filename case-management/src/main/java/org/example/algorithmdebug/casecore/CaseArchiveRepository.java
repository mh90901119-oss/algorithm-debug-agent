package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.CodePathCollectionPlan;
import org.example.algorithmdebug.contracts.MethodCatalog;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.CollectionId;
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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
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
            throw new IllegalArgumentException("Case Archive 依赖不能为空");
        }
        this.casesRoot = casesRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.casesRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_PATH_INVALID", "项目 Case 根目录不存在或不是普通目录");
        }
        this.mapper = mapper;
        this.writer = writer;
    }

    /** 创建新 Case 目录和不可变身份清单。 */
    public void createCase(CaseManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest 不能为空");
        }
        CaseArchiveLayout layout = layout(manifest.caseId());
        try {
            Files.createDirectory(layout.caseRoot());
            Files.createDirectory(layout.contextsRoot());
            Files.createDirectory(layout.analysesRoot());
            Files.createDirectory(layout.runsRoot());
            Files.createDirectory(layout.evidenceRoot());
            Files.createDirectory(layout.collectionsRoot());
            writer.writeNew(layout.caseDocument(), mapper.writeJson(manifest));
        } catch (FileAlreadyExistsException | WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 在已有 Case 下追加一个 Context。 */
    public void createContext(ContextSnapshot context) {
        CaseManifest manifest = requireCase(requireNonNull(context, "context").caseId());
        requireCaseIdentity(manifest, context.projectId(), context.targetTest());
        Path document = layout(context.caseId()).contextDocument(context.contextId());
        createChildDocument(document, context);
    }

    /** 在已有 Case/Context 下追加一个 Analysis 请求。 */
    public void createAnalysis(AnalysisRequest analysis) {
        AnalysisRequest checked = requireNonNull(analysis, "analysis");
        requireCase(checked.caseId());
        requireContext(checked.caseId(), checked.contextId());
        CaseArchiveLayout layout = layout(checked.caseId());
        try {
            Files.createDirectory(layout.analysisRoot(checked.analysisId()));
            Files.createDirectory(layout.analysisPlansRoot(checked.analysisId()));
            writer.writeNew(layout.analysisDocument(checked.analysisId()), mapper.writeJson(checked));
        } catch (FileAlreadyExistsException | WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 为已有 Analysis 原子创建静态方法目录；同一 Analysis 不得覆盖。 */
    public Path createMethodCatalog(MethodCatalog catalog) {
        MethodCatalog checked = requireNonNull(catalog, "catalog");
        CaseManifest manifest = requireCase(checked.caseId());
        ContextSnapshot context = requireContext(checked.caseId(), checked.contextId());
        AnalysisRequest analysis = requireAnalysis(checked.caseId(), checked.analysisId());
        if (!analysis.contextId().equals(checked.contextId())
                || !manifest.targetTest().equals(checked.targetTest())
                || !context.sourceSnapshot().sha256().equals(checked.sourceFingerprintSha256())) {
            throw identityMismatch("MethodCatalog 与 Case/Context/Analysis 身份不一致");
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
            throw new WorkspaceException("METHOD_CATALOG_NOT_FOUND", "Case 归档文档不存在");
        }
        MethodCatalog value;
        try {
            value = mapper.readJsonArtifact(document, MethodCatalog.class);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "MethodCatalog 归档文档无效", failure);
        }
        if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())) {
            throw identityMismatch("MethodCatalog 文档身份与路径不一致");
        }
        return value;
    }

    /** 为已有 MethodCatalog 原子创建 CodePath 计划；计划 ID 不得覆盖。 */
    public Path createCodePathPlan(CodePathCollectionPlan plan) {
        CodePathCollectionPlan checked = requireNonNull(plan, "plan");
        MethodCatalog catalog = requireMethodCatalog(checked.caseId(), checked.analysisId());
        if (!catalog.contextId().equals(checked.contextId())
                || !catalog.targetTest().equals(checked.targetTest())
                || !catalog.sourceFingerprintSha256().equals(checked.sourceFingerprintSha256())) {
            throw identityMismatch("CodePath 计划与 MethodCatalog 身份不一致");
        }
        validatePlanSelectors(catalog, checked);
        Path document = layout(checked.caseId()).planDocument(
                checked.analysisId(), checked.planId());
        try {
            writer.writeNew(document, mapper.writeJson(checked));
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
        for (var selector : plan.selectors()) {
            if (!selectorKeys.add(selector.methodKey())) {
                throw identityMismatch("CodePath 计划包含重复 selector: " + selector.methodKey());
            }
            var entry = catalogEntries.get(selector.methodKey());
            if (entry == null) {
                throw identityMismatch("CodePath selector 不属于 MethodCatalog: " + selector.methodKey());
            }
            var anchor = entry.sourceAnchor();
            if (!selector.className().equals(anchor.className())
                    || !selector.methodName().equals(anchor.methodName())
                    || !selector.descriptor().equals(anchor.descriptor())
                    || !selector.sourceSha256().equals(anchor.sourceSha256())) {
                throw identityMismatch("CodePath selector 与 MethodCatalog SourceAnchor 不一致");
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
            throw identityMismatch("CodePath 计划文档身份与路径不一致");
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
                    "CODEPATH_PLAN_NOT_FOUND", "CodePath 计划不存在或 PlanId 在 Analysis 间不唯一");
        }
        String analysisSegment = matches.getFirst().getParent().getParent().getFileName().toString();
        AnalysisId analysisId;
        try {
            analysisId = new AnalysisId(analysisSegment);
        } catch (IllegalArgumentException failure) {
            throw new WorkspaceException("CASE_DOCUMENT_INVALID", "CodePath 计划路径身份无效", failure);
        }
        return requireCodePathPlan(caseId, analysisId, planId);
    }

    /** 为已有 MethodCatalog 原子创建 JDWP 计划；计划 ID 不得覆盖。 */
    public Path createJdwpPlan(JdwpCollectionPlan plan) {
        JdwpCollectionPlan checked = requireNonNull(plan, "plan");
        MethodCatalog catalog = requireMethodCatalog(checked.caseId(), checked.analysisId());
        if (!catalog.contextId().equals(checked.contextId())
                || !catalog.targetTest().equals(checked.targetTest())
                || !catalog.sourceFingerprintSha256().equals(checked.sourceFingerprintSha256())) {
            throw identityMismatch("JDWP 计划与 MethodCatalog 身份不一致");
        }
        var entries = new HashMap<String, org.example.algorithmdebug.contracts.MethodCatalogEntry>();
        catalog.entries().forEach(entry -> entries.put(entry.methodKey(), entry));
        for (var point : checked.tracepoints()) {
            var entry = entries.get(point.methodKey());
            if (entry == null || !entry.sourceAnchor().equals(point.sourceAnchor())) {
                throw identityMismatch("JDWP tracepoint 不属于 MethodCatalog 或源码锚点不一致");
            }
        }
        Path document = layout(checked.caseId()).planDocument(
                checked.analysisId(), checked.planId());
        try {
            writer.writeNew(document, mapper.writeJson(checked));
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
            throw identityMismatch("JDWP 计划文档身份与路径不一致");
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
                    "JDWP_PLAN_NOT_FOUND", "JDWP 计划不存在或 PlanId 在 Analysis 间不唯一");
        }
        String analysisSegment = matches.getFirst().getParent().getParent().getFileName().toString();
        try {
            return requireJdwpPlan(caseId, new AnalysisId(analysisSegment), planId);
        } catch (IllegalArgumentException failure) {
            throw new WorkspaceException("CASE_DOCUMENT_INVALID", "JDWP 计划路径身份无效", failure);
        }
    }

    /**
     * 在外部 Collector 启动前创建 Collection 目录、raw/derived/logs 子目录和请求文档。
     *
     * @return 已创建的 Collection 绝对目录
     */
    public Path startMethodPathCollection(MethodPathCollectionRecord record) {
        MethodPathCollectionRecord checked = requireNonNull(record, "record");
        CodePathCollectionPlan plan = requireCodePathPlan(
                checked.caseId(), checked.analysisId(), checked.planId());
        if (!plan.contextId().equals(checked.contextId())
                || !plan.analysisId().equals(checked.analysisId())
                || !plan.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("Collection 请求与 CodePath 计划身份不一致");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        Path root = layout.collectionRoot(checked.collectionId());
        try {
            Files.createDirectory(root);
            Files.createDirectory(root.resolve("raw"));
            Files.createDirectory(root.resolve("derived"));
            Files.createDirectory(root.resolve("logs"));
            Files.createDirectory(root.resolve("validation"));
            writer.writeNew(layout.collectionRequest(checked.collectionId()), mapper.writeJson(checked));
            return root;
        } catch (FileAlreadyExistsException | WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 在外部双进程启动前追加 JDWP Collection 请求和固定目录。 */
    public Path startJdwpCollection(JdwpCollectionRecord record) {
        JdwpCollectionRecord checked = requireNonNull(record, "record");
        JdwpCollectionPlan plan = requireJdwpPlan(
                checked.caseId(), checked.analysisId(), checked.planId());
        if (!plan.contextId().equals(checked.contextId())
                || !plan.targetTest().equals(checked.targetTest())) {
            throw identityMismatch("JDWP Collection 请求与计划身份不一致");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        Path root = layout.collectionRoot(checked.collectionId());
        try {
            Files.createDirectory(root);
            Files.createDirectory(root.resolve("raw"));
            Files.createDirectory(root.resolve("derived"));
            Files.createDirectory(root.resolve("logs"));
            Files.createDirectory(root.resolve("validation"));
            writer.writeNew(layout.collectionRequest(checked.collectionId()), mapper.writeJson(checked));
            return root;
        } catch (FileAlreadyExistsException | WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException | SecurityException failure) {
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
            throw identityMismatch("JDWP Collection 请求文档身份与路径不一致");
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
            throw identityMismatch("Collection 请求文档身份与路径不一致");
        }
        return value;
    }

    /** 为已启动 Collection 原子追加 Baseline 检查。 */
    public Path createCollectionBaselineCheck(CollectionBaselineCheck check) {
        CollectionBaselineCheck checked = requireNonNull(check, "check");
        MethodPathCollectionRecord request = requireMethodPathCollection(
                checked.caseId(), checked.collectionId());
        if (!request.contextId().equals(checked.contextId())
                || !request.analysisId().equals(checked.analysisId())
                || !request.runId().equals(checked.runId())) {
            throw identityMismatch("Baseline check 与 Collection 请求身份不一致");
        }
        Path document = layout(checked.caseId()).collectionBaselineCheck(checked.collectionId());
        try {
            writer.writeNew(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 为已启动 JDWP Collection 原子追加 Baseline 检查。 */
    public Path createJdwpCollectionBaselineCheck(CollectionBaselineCheck check) {
        CollectionBaselineCheck checked = requireNonNull(check, "check");
        JdwpCollectionRecord request = requireJdwpCollection(
                checked.caseId(), checked.collectionId());
        if (!request.contextId().equals(checked.contextId())
                || !request.analysisId().equals(checked.analysisId())
                || !request.runId().equals(checked.runId())) {
            throw identityMismatch("Baseline check 与 JDWP Collection 请求身份不一致");
        }
        Path document = layout(checked.caseId()).collectionBaselineCheck(checked.collectionId());
        try {
            writer.writeNew(document, mapper.writeJson(checked));
            return document;
        } catch (WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 在归一化开始前创建 Evidence 目录和不可变构建请求。 */
    public Path createEvidenceRequest(EvidenceBuildRequest request) {
        EvidenceBuildRequest checked = requireNonNull(request, "request");
        requireCase(checked.caseId());
        requireContext(checked.caseId(), checked.contextId());
        AnalysisRequest analysis = requireAnalysis(checked.caseId(), checked.analysisId());
        if (!analysis.contextId().equals(checked.contextId())) {
            throw identityMismatch("Evidence 请求 Context 与 Analysis 不一致");
        }
        RunRequest run = requireRunRequest(checked.caseId(), checked.runId());
        if (!run.contextId().equals(checked.contextId())) {
            throw identityMismatch("Evidence 请求 Run 与 Context 不一致");
        }
        if (findRunOutcome(checked.caseId(), checked.runId()).isEmpty()) {
            throw identityMismatch("Evidence 请求只能引用已完成的 Run");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        Path root = layout.evidenceRoot(checked.evidenceId());
        try {
            Files.createDirectory(root);
            Path document = layout.evidenceBuildRequest(checked.evidenceId());
            writer.writeNew(document, mapper.writeJson(checked));
            return document;
        } catch (FileAlreadyExistsException | WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 读取不可变 Evidence 构建请求。 */
    public EvidenceBuildRequest requireEvidenceRequest(CaseId caseId, EvidenceId evidenceId) {
        EvidenceBuildRequest value = requireDocument(
                layout(caseId).evidenceBuildRequest(evidenceId),
                EvidenceBuildRequest.class, "EVIDENCE_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !evidenceId.equals(value.evidenceId())) {
            throw identityMismatch("Evidence 请求身份与路径不一致");
        }
        return value;
    }

    /** 原子追加一次归一化清单。 */
    public Path createNormalizationManifest(NormalizationManifest manifest) {
        NormalizationManifest checked = requireNonNull(manifest, "manifest");
        requireEvidenceCollectionIdentity(
                checked.caseId(), checked.contextId(), checked.analysisId(),
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
                checked.caseId(), checked.contextId(), checked.analysisId(),
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
                checked.caseId(), checked.contextId(), checked.analysisId(),
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
                checked.caseId(), checked.contextId(), checked.analysisId(),
                checked.runId(), checked.planId(), checked.collectionId(),
                checked.evidenceId(), checked.collectorType());
        return createP4Document(layout(checked.caseId()).collectionValidation(
                checked.collectionId(), checked.evidenceId()), checked,
                BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 原子追加面向模型的 Evidence Bundle。 */
    public Path createEvidenceBundle(EvidenceBundle bundle) {
        EvidenceBundle checked = requireNonNull(bundle, "bundle");
        requireEvidenceIdentity(checked.caseId(), checked.contextId(),
                checked.analysisId(), checked.evidenceId());
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
            throw identityMismatch("Evidence Bundle 身份与路径不一致");
        }
        return value;
    }

    /** 原子追加请求维度的充分性评估。 */
    public Path createSufficiencyEvaluation(SufficiencyEvaluation evaluation) {
        SufficiencyEvaluation checked = requireNonNull(evaluation, "evaluation");
        requireEvidenceIdentity(checked.caseId(), checked.contextId(),
                checked.analysisId(), checked.evidenceId());
        return createP4Document(layout(checked.caseId()).sufficiencyEvaluation(
                checked.evidenceId()), checked, BoundedDocumentMapper.MAX_DOCUMENT_BYTES);
    }

    /** 在启动外部 Maven 进程前创建 Run 目录、raw 目录和 RunRequest。 */
    public void startRun(RunRequest request) {
        RunRequest checked = requireNonNull(request, "request");
        CaseManifest manifest = requireCase(checked.caseId());
        requireContext(checked.caseId(), checked.contextId());
        AnalysisRequest analysis = requireAnalysis(checked.caseId(), checked.analysisId());
        if (!analysis.contextId().equals(checked.contextId())) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_IDENTITY_MISMATCH", "RunRequest Context 与 Analysis 不一致");
        }
        if (!manifest.targetTest().equals(checked.targetTest())) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_IDENTITY_MISMATCH", "RunRequest 目标 UT 与 Case 不一致");
        }
        CaseArchiveLayout layout = layout(checked.caseId());
        Path runRoot = layout.runRoot(checked.runId());
        try {
            Files.createDirectory(runRoot);
            Files.createDirectory(layout.runRaw(checked.runId()));
            writer.writeNew(layout.runRequest(checked.runId()), mapper.writeJson(checked));
        } catch (FileAlreadyExistsException | WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    /** 为已启动 Run 原子创建最终 RunOutcome；不得覆盖已有结果。 */
    public void completeRun(RunOutcomeSummary outcome) {
        RunOutcomeSummary checked = requireNonNull(outcome, "outcome");
        RunRequest request = requireRunRequest(checked.caseId(), checked.runId());
        if (!request.contextId().equals(checked.contextId())
                || !request.analysisId().equals(checked.analysisId())) {
            throw new WorkspaceException(
                    "CASE_ARCHIVE_IDENTITY_MISMATCH", "RunOutcome 与 RunRequest 归属不一致");
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

    /** 查找指定 Context 的不可变复现参考；不存在时返回空。 */
    public Optional<RunResultFingerprint> findReproduction(
            CaseId caseId, ContextId contextId) {
        requireContext(caseId, contextId);
        Path document = layout(caseId).contextReproduction(contextId);
        if (!Files.exists(document, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "Context reproduction 不是普通文件");
        }
        RunResultFingerprint value = readFingerprint(document);
        validateFingerprintPathIdentity(value, caseId, contextId);
        validateFingerprintRunIdentity(value);
        return Optional.of(value);
    }

    /**
     * 原子建立 Context 的首个复现参考；已有参考时只读返回，绝不覆盖。
     *
     * @return 新建或已经存在的参考
     */
    public RunResultFingerprint createReproductionIfAbsent(
            RunResultFingerprint fingerprint) {
        RunResultFingerprint checked = requireNonNull(fingerprint, "fingerprint");
        requireContext(checked.caseId(), checked.contextId());
        validateFingerprintRunIdentity(checked);
        Optional<RunResultFingerprint> existing =
                findReproduction(checked.caseId(), checked.contextId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        requirePersistedRunFingerprint(checked);
        Path document = layout(checked.caseId()).contextReproduction(checked.contextId());
        try {
            writer.writeNew(document, mapper.writeJson(checked));
            return checked;
        } catch (WorkspaceException failure) {
            Optional<RunResultFingerprint> concurrentlyCreated =
                    findReproduction(checked.caseId(), checked.contextId());
            if (concurrentlyCreated.isPresent()) {
                return concurrentlyCreated.orElseThrow();
            }
            throw archiveWriteFailure(failure);
        }
    }

    /**
     * 按 Context 创建时间和 ID 的确定性顺序查找当前 Context 之前最近的复现参考。
     */
    public Optional<RunResultFingerprint> findLatestReproductionBefore(
            CaseId caseId, ContextId currentContextId) {
        ContextSnapshot current = requireContext(caseId, currentContextId);
        Comparator<ContextSnapshot> order = Comparator
                .comparing(ContextSnapshot::createdAt)
                .thenComparing(value -> value.contextId().value());
        List<ContextSnapshot> candidates = childDirectories(layout(caseId).contextsRoot()).stream()
                .filter(path -> Files.isRegularFile(
                        path.resolve("context.json"), LinkOption.NOFOLLOW_LINKS))
                .map(path -> contextIdFromDirectory(path))
                .map(contextId -> requireContext(caseId, contextId))
                .filter(candidate -> order.compare(candidate, current) < 0)
                .sorted(order.reversed())
                .toList();
        for (ContextSnapshot candidate : candidates) {
            Optional<RunResultFingerprint> reference =
                    findReproduction(caseId, candidate.contextId());
            if (reference.isPresent()) {
                return reference;
            }
        }
        return Optional.empty();
    }

    /** 读取 Case 身份；不存在或损坏时返回结构化错误。 */
    public CaseManifest requireCase(CaseId caseId) {
        return requireDocument(layout(caseId).caseDocument(), CaseManifest.class, "CASE_NOT_FOUND");
    }

    /** 读取指定 Context。 */
    public ContextSnapshot requireContext(CaseId caseId, ContextId contextId) {
        ContextSnapshot value = requireDocument(
                layout(caseId).contextDocument(contextId), ContextSnapshot.class, "CONTEXT_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !contextId.equals(value.contextId())) {
            throw identityMismatch("Context 文档身份与路径不一致");
        }
        return value;
    }

    /** 读取指定 Analysis。 */
    public AnalysisRequest requireAnalysis(CaseId caseId, AnalysisId analysisId) {
        AnalysisRequest value = requireDocument(
                layout(caseId).analysisDocument(analysisId), AnalysisRequest.class, "ANALYSIS_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !analysisId.equals(value.analysisId())) {
            throw identityMismatch("Analysis 文档身份与路径不一致");
        }
        return value;
    }

    /** 读取指定 RunRequest。 */
    public RunRequest requireRunRequest(CaseId caseId, RunId runId) {
        RunRequest value = requireDocument(
                layout(caseId).runRequest(runId), RunRequest.class, "RUN_NOT_FOUND");
        if (!caseId.equals(value.caseId()) || !runId.equals(value.runId())) {
            throw identityMismatch("RunRequest 文档身份与路径不一致");
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
            throw identityMismatch("RunOutcome 文档身份与路径不一致");
        }
        return Optional.of(value);
    }

    /** @return 指定 Run 中可写入原始产物的已创建目录 */
    public Path runRawDirectory(CaseId caseId, RunId runId) {
        requireRunRequest(caseId, runId);
        return layout(caseId).runRaw(runId);
    }

    CaseArchiveLayout layout(CaseId caseId) {
        return CaseArchiveLayout.of(casesRoot, caseId);
    }

    BoundedDocumentMapper mapper() {
        return mapper;
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
                    "CASE_ARCHIVE_READ_FAILED", "无法枚举 Case 子目录", failure);
        }
    }

    private void createChildDocument(Path document, Object value) {
        try {
            Files.createDirectory(document.getParent());
            writer.writeNew(document, mapper.writeJson(value));
        } catch (FileAlreadyExistsException | WorkspaceException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    private Path createP4Document(Path document, Object value, long maximumBytes) {
        try {
            Files.createDirectories(document.getParent());
            writer.writeNew(document, maximumBytes,
                    output -> mapper.writeJsonArtifact(output, value));
            return document;
        } catch (WorkspaceException | SecurityException failure) {
            throw archiveWriteFailure(failure);
        } catch (IOException failure) {
            throw archiveWriteFailure(failure);
        }
    }

    private void requireEvidenceCollectionIdentity(
            CaseId caseId,
            ContextId contextId,
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
            throw identityMismatch("派生产物与 Evidence 请求身份或 Collection 角色不一致");
        }
        if (currentEvidence && !request.contextId().equals(contextId)) {
            throw identityMismatch("当前 Evidence Collection 必须属于请求 Context");
        }
        if (comparisonEvidence && request.contextId().equals(contextId)) {
            throw identityMismatch("同 Context 历史 Collection 应作为当前证据复用而非比较证据");
        }
        boolean matched;
        if ("CODEPATH".equals(collectorType)) {
            MethodPathCollectionRecord collection = requireMethodPathCollection(caseId, collectionId);
            matched = collection.contextId().equals(contextId)
                    && collection.analysisId().equals(analysisId)
                    && collection.runId().equals(runId)
                    && collection.planId().equals(planId);
        } else if ("JDWP".equals(collectorType)) {
            JdwpCollectionRecord collection = requireJdwpCollection(caseId, collectionId);
            matched = collection.contextId().equals(contextId)
                    && collection.analysisId().equals(analysisId)
                    && collection.runId().equals(runId)
                    && collection.planId().equals(planId);
        } else {
            throw identityMismatch("未知 Collector 类型不能归档派生产物");
        }
        if (!matched) {
            throw identityMismatch("派生产物与原 Collection 的 Context/Analysis/Run/Plan 不一致");
        }
    }

    private void requireEvidenceIdentity(
            CaseId caseId,
            ContextId contextId,
            AnalysisId analysisId,
            EvidenceId evidenceId) {
        EvidenceBuildRequest request = requireEvidenceRequest(caseId, evidenceId);
        if (!request.contextId().equals(contextId) || !request.analysisId().equals(analysisId)) {
            throw identityMismatch("Evidence 产物与构建请求身份不一致");
        }
    }

    private <T> T requireDocument(Path document, Class<T> type, String missingCode) {
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(missingCode, "Case 归档文档不存在");
        }
        try {
            return mapper.readJson(document, type);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "Case 归档文档无效", failure);
        }
    }

    private RunResultFingerprint readFingerprint(Path document) {
        try {
            return mapper.readJson(document, RunResultFingerprint.class);
        } catch (WorkspaceException failure) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "RunResultFingerprint 文档无效", failure);
        }
    }

    private void requirePersistedRunFingerprint(RunResultFingerprint expected) {
        Path document = layout(expected.caseId()).runResultFingerprint(expected.runId());
        if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException(
                    "RUN_RESULT_FINGERPRINT_NOT_FOUND", "Run 结果指纹尚未保存");
        }
        RunResultFingerprint persisted = readFingerprint(document);
        validateFingerprintPathIdentity(
                persisted, expected.caseId(), expected.contextId());
        if (!persisted.equals(expected)) {
            throw identityMismatch("Context reproduction 与 Run 结果指纹不一致");
        }
    }

    private void validateFingerprintRunIdentity(RunResultFingerprint fingerprint) {
        RunRequest request = requireRunRequest(fingerprint.caseId(), fingerprint.runId());
        if (!request.contextId().equals(fingerprint.contextId())) {
            throw identityMismatch("RunResultFingerprint Context 与 RunRequest 不一致");
        }
    }

    private static void validateFingerprintPathIdentity(
            RunResultFingerprint fingerprint,
            CaseId caseId,
            ContextId contextId) {
        if (!caseId.equals(fingerprint.caseId())
                || !contextId.equals(fingerprint.contextId())) {
            throw identityMismatch("RunResultFingerprint 文档身份与路径不一致");
        }
    }

    private static ContextId contextIdFromDirectory(Path directory) {
        try {
            return new ContextId(directory.getFileName().toString());
        } catch (IllegalArgumentException failure) {
            throw new WorkspaceException(
                    "CASE_DOCUMENT_INVALID", "Context 目录名不是有效 ID", failure);
        }
    }

    private static void requireCaseIdentity(
            CaseManifest manifest,
            org.example.algorithmdebug.contracts.ProjectId projectId,
            org.example.algorithmdebug.contracts.TargetTest targetTest) {
        if (!manifest.projectId().equals(projectId) || !manifest.targetTest().equals(targetTest)) {
            throw identityMismatch("Context 与 Case 项目或目标 UT 不一致");
        }
    }

    private static WorkspaceException identityMismatch(String message) {
        return new WorkspaceException("CASE_ARCHIVE_IDENTITY_MISMATCH", message);
    }

    private static WorkspaceException archiveWriteFailure(Throwable failure) {
        return new WorkspaceException(
                "CASE_ARCHIVE_WRITE_FAILED", "无法安全追加 Case 归档文档", failure);
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
