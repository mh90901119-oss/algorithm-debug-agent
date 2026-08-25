package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.CaseDigestReader;
import org.example.algorithmdebug.casecore.CaseSessionRequest;
import org.example.algorithmdebug.casecore.CaseSessionService;
import org.example.algorithmdebug.casecore.ContextMode;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.RegisteredArtifactReader;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisResult;
import org.example.algorithmdebug.contracts.ArtifactTextExcerpt;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

/** 编排已登记算法模块的 Case 打开和只读检查；打开 Case 不执行目标 UT。 */
public final class CaseApplicationService {

    private final ProjectRegistrationRepository registrations;
    private final BoundedDocumentMapper mapper;
    private final AtomicDocumentWriter writer;
    private final AdapterCatalog adapters;
    private final OpaqueIdGenerator ids;
    private final Clock clock;

    /** 注入项目登记、归档、Adapter、ID 与时钟端口。 */
    public CaseApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock) {
        if (registrations == null || mapper == null || writer == null || adapters == null
                || ids == null || clock == null) {
            throw new IllegalArgumentException("CaseApplicationService 依赖不能为空");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.adapters = adapters;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * 新建或继续一个 Case Analysis，不运行 Maven，也不扫描源码、输入或 POM。
     *
     * @param contextMode 显式决定复用最近 Context 或创建新 Context
     */
    public CaseOpenResult open(
            Path workspaceRoot,
            ProjectId projectId,
            TargetTest targetTest,
            String question,
            Optional<CaseId> caseId,
            Optional<String> adapterId,
            ContextMode contextMode) {
        if (targetTest == null || question == null || caseId == null || adapterId == null
                || contextMode == null) {
            throw new IllegalArgumentException("Case open 参数不能为空");
        }
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            ProjectRegistration registration = requireRegistration(layout, projectId);
            Path moduleRoot = Path.of(registration.moduleRoot()).toAbsolutePath().normalize();
            AdapterCatalog.AdapterSelection selection = adapters.select(moduleRoot, adapterId);
            CaseArchiveRepository archive = archive(layout, projectId);
            CaseOpenResult opened = new CaseSessionService(
                    archive, new CaseDigestReader(archive), ids, clock).open(
                    new CaseSessionRequest(
                            caseId, projectId, targetTest,
                            selection.adapter().descriptor().adapterId(), question, contextMode));
            return new CaseOpenResult(
                    opened.caseId(), opened.contextId(), opened.analysisId(),
                    opened.caseCreated(), opened.contextCreated(),
                    Optional.ofNullable(registration.resultJsonDirectory()), opened.digest());
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "打开 Case 失败", failure);
        }
    }

    /** 从不可变子文档重建一个 Case 的有界摘要，不执行 Maven。 */
    public CaseDigest inspect(Path workspaceRoot, ProjectId projectId, CaseId caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            return new CaseDigestReader(archive).read(caseId);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "检查 Case 失败", failure);
        }
    }

    /** 原子完成一轮 Analysis，只归档最终回答、分级结论和显式引用。 */
    public AnalysisResult completeAnalysis(
            Path workspaceRoot, ProjectId projectId, CaseId caseId,
            AnalysisId analysisId, AnalysisResult result) {
        if (caseId == null || analysisId == null || result == null) {
            throw new IllegalArgumentException("analysis complete 参数不能为空");
        }
        if (!caseId.equals(result.caseId()) || !analysisId.equals(result.analysisId())) {
            throw new CaseRunException(
                    "ANALYSIS_RESULT_IDENTITY_MISMATCH", "Analysis 结果与命令身份不一致");
        }
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            archive.completeAnalysis(result);
            return result;
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "完成 Analysis 失败", failure);
        }
    }

    /** 只按已注册 Artifact ID 读取有界 UTF-8 片段。 */
    public ArtifactTextExcerpt readArtifact(
            Path workspaceRoot, ProjectId projectId, CaseId caseId,
            String artifactId, long offsetBytes, int maxBytes) {
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            return new RegisteredArtifactReader(archive).read(
                    caseId, artifactId, offsetBytes, maxBytes);
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "读取 Artifact 失败", failure);
        }
    }

    private ProjectRegistration requireRegistration(WorkspaceLayout layout, ProjectId projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        return registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "项目尚未登记: " + projectId.value()));
    }

    private CaseArchiveRepository archive(WorkspaceLayout layout, ProjectId projectId) {
        return new CaseArchiveRepository(layout.projectCases(projectId), mapper, writer);
    }
}
