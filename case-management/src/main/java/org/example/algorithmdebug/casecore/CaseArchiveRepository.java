package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.RunOutcomeSummary;
import org.example.algorithmdebug.contracts.RunRequest;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
        createChildDocument(layout(checked.caseId()).analysisDocument(checked.analysisId()), checked);
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
