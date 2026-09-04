package org.example.algorithmdebug.core;

import org.example.algorithmdebug.casecore.AtomicDocumentWriter;
import org.example.algorithmdebug.casecore.BoundedDocumentMapper;
import org.example.algorithmdebug.casecore.CaseArchiveRepository;
import org.example.algorithmdebug.casecore.CaseDigestReader;
import org.example.algorithmdebug.casecore.CaseSessionRequest;
import org.example.algorithmdebug.casecore.CaseSessionService;
import org.example.algorithmdebug.casecore.OpaqueIdGenerator;
import org.example.algorithmdebug.casecore.RegisteredArtifactReader;
import org.example.algorithmdebug.casecore.RegisteredEvidenceQuery;
import org.example.algorithmdebug.casecore.ProjectRegistrationRepository;
import org.example.algorithmdebug.casecore.WorkspaceException;
import org.example.algorithmdebug.casecore.WorkspaceLayout;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactTextExcerpt;
import org.example.algorithmdebug.contracts.EvidenceQueryFilter;
import org.example.algorithmdebug.contracts.EvidenceQueryResult;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.ProjectRegistration;
import org.example.algorithmdebug.contracts.TargetTest;
import org.example.algorithmdebug.casecore.logging.AgentExecutionLog;
import org.example.algorithmdebug.casecore.logging.AgentLogContext;

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
    private final AgentExecutionLog executionLog;

    /** 注入项目登记、归档、Adapter、ID 与时钟端口。 */
    public CaseApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock) {
        this(registrations, mapper, writer, adapters, ids, clock, AgentExecutionLog.disabled());
    }

    public CaseApplicationService(
            ProjectRegistrationRepository registrations,
            BoundedDocumentMapper mapper,
            AtomicDocumentWriter writer,
            AdapterCatalog adapters,
            OpaqueIdGenerator ids,
            Clock clock,
            AgentExecutionLog executionLog) {
        if (registrations == null || mapper == null || writer == null || adapters == null
                || ids == null || clock == null || executionLog == null) {
            throw new IllegalArgumentException("CaseApplicationService dependencies must not be null");
        }
        this.registrations = registrations;
        this.mapper = mapper;
        this.writer = writer;
        this.adapters = adapters;
        this.ids = ids;
        this.clock = clock;
        this.executionLog = executionLog;
    }

    /**
     * 新建或继续一个 Case Analysis，不运行 Maven，也不扫描源码、输入或 POM。
     *
     */
    public CaseOpenResult open(
            Path workspaceRoot,
            ProjectId projectId,
            TargetTest targetTest,
            String question,
            Optional<CaseId> caseId,
            Optional<String> adapterId) {
        if (targetTest == null || question == null || caseId == null || adapterId == null) {
            throw new IllegalArgumentException("Case open parameters must not be null");
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
                            selection.adapter().descriptor().adapterId(), question));
            CaseOpenResult result = new CaseOpenResult(
                    opened.caseId(), opened.analysisId(),
                    opened.caseCreated(),
                    Optional.ofNullable(registration.resultJsonDirectory()), opened.digest());
            AgentLogContext logContext = AgentLogContext.forCase(
                    workspaceRoot, projectId, result.caseId()).withAnalysis(result.analysisId());
            executionLog.info(logContext, "CaseApplicationService", "CASE_OPEN_STARTED",
                    "STARTED", "Case open processing started");
            executionLog.info(logContext, "CaseApplicationService",
                    result.caseCreated() ? "CASE_CREATED" : "CASE_REUSED",
                    result.caseCreated() ? "CREATED" : "REUSED", "Case identity was resolved");
            executionLog.info(logContext, "CaseApplicationService", "ANALYSIS_CREATED",
                    "CREATED", "Analysis was created");
            executionLog.info(logContext, "CaseApplicationService", "CASE_OPEN_COMPLETED",
                    "COMPLETED", "Case open processing completed");
            return result;
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Failed to open Case", failure);
        }
    }

    /** 从不可变子文档重建一个 Case 的有界摘要，不执行 Maven。 */
    public CaseDigest inspect(Path workspaceRoot, ProjectId projectId, CaseId caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("caseId must not be null");
        }
        AgentLogContext logContext = AgentLogContext.forCase(workspaceRoot, projectId, caseId);
        executionLog.info(logContext, "CaseApplicationService", "CASE_INSPECT_STARTED",
                "STARTED", "Case inspection started");
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            CaseDigest result = new CaseDigestReader(archive).read(caseId);
            executionLog.info(logContext, "CaseApplicationService", "CASE_INSPECT_COMPLETED",
                    "COMPLETED", "Case inspection completed");
            return result;
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Failed to inspect Case", failure);
        }
    }

    /** 只按已注册 Artifact ID 读取有界 UTF-8 片段。 */
    public ArtifactTextExcerpt readArtifact(
            Path workspaceRoot, ProjectId projectId, CaseId caseId,
            String artifactId, long offsetBytes, int maxBytes) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withArtifact(artifactId);
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            ArtifactTextExcerpt excerpt = new RegisteredArtifactReader(archive).read(
                    caseId, artifactId, offsetBytes, maxBytes);
            executionLog.info(logContext, "CaseApplicationService",
                    excerpt.truncated() ? "ARTIFACT_READ_TRUNCATED" : "ARTIFACT_READ_COMPLETED",
                    excerpt.truncated() ? "PARTIAL" : "COMPLETED", "Artifact excerpt was read");
            return excerpt;
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Read Artifact failed", failure);
        }
    }

    /** 查询已注册 CodePath/JDWP 派生证据中的有界记录集合。 */
    public EvidenceQueryResult queryEvidence(
            Path workspaceRoot, ProjectId projectId, CaseId caseId,
            String artifactId, EvidenceQueryFilter filter,
            int offset, int limit, int maxBytes) {
        AgentLogContext logContext = AgentLogContext.forCase(
                workspaceRoot, projectId, caseId).withArtifact(artifactId);
        try {
            WorkspaceLayout layout = WorkspaceLayout.of(workspaceRoot);
            requireRegistration(layout, projectId);
            CaseArchiveRepository archive = archive(layout, projectId);
            EvidenceQueryResult result = new RegisteredEvidenceQuery(archive).query(
                    caseId, artifactId, filter, offset, limit, maxBytes);
            executionLog.info(logContext, "CaseApplicationService",
                    result.truncated() ? "EVIDENCE_QUERY_TRUNCATED" : "EVIDENCE_QUERY_COMPLETED",
                    result.truncated() ? "PARTIAL" : "COMPLETED",
                    "Evidence query completed; scanned=" + result.scannedRecords()
                            + "; matched=" + result.matchedRecords()
                            + "; returned=" + result.returnedRecords());
            return result;
        } catch (WorkspaceException failure) {
            throw new CaseRunException(failure.code(), "Evidence Query failed", failure);
        }
    }

    private ProjectRegistration requireRegistration(WorkspaceLayout layout, ProjectId projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        return registrations.findById(layout, projectId).orElseThrow(() ->
                new CaseRunException("PROJECT_NOT_REGISTERED", "Project is not registered: " + projectId.value()));
    }

    private CaseArchiveRepository archive(WorkspaceLayout layout, ProjectId projectId) {
        return new CaseArchiveRepository(layout.projectCases(projectId), mapper, writer);
    }
}
