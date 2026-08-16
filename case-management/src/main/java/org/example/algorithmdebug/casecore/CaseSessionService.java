package org.example.algorithmdebug.casecore;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextSnapshot;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** 实现显式 Case 续接规则，追加 Context/Analysis，但绝不运行目标 UT。 */
public final class CaseSessionService {

    private final CaseArchiveRepository repository;
    private final CaseDigestReader digestReader;
    private final ContextSnapshotBuilder snapshotBuilder;
    private final OpaqueIdGenerator ids;
    private final Clock clock;

    /** 注入持久化、快照、ID 与时间端口。 */
    public CaseSessionService(
            CaseArchiveRepository repository,
            CaseDigestReader digestReader,
            ContextSnapshotBuilder snapshotBuilder,
            OpaqueIdGenerator ids,
            Clock clock) {
        if (repository == null || digestReader == null || snapshotBuilder == null
                || ids == null || clock == null) {
            throw new IllegalArgumentException("CaseSessionService 依赖不能为空");
        }
        this.repository = repository;
        this.digestReader = digestReader;
        this.snapshotBuilder = snapshotBuilder;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * 新建或续接 Case，并为本次问题创建 Analysis；该方法不启动外部进程。
     *
     * @param request 显式项目、UT、问题和可见上下文
     * @return 新 Analysis 身份和重建后的 Case Digest
     */
    public CaseOpenResult open(CaseSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        Instant now = clock.instant();
        boolean caseCreated = request.caseId().isEmpty();
        CaseId caseId = request.caseId().orElseGet(ids::newCaseId);
        CaseManifest manifest;
        if (caseCreated) {
            manifest = new CaseManifest(
                    SchemaVersions.CASE_MANIFEST, caseId, request.projectId(),
                    request.targetTest(), request.question(), now);
            repository.createCase(manifest);
        } else {
            manifest = repository.requireCase(caseId);
            validateExistingCase(manifest, request);
        }

        ContextId candidateId = ids.newContextId();
        ContextSnapshot candidate = snapshotBuilder.build(new ContextSnapshotRequest(
                caseId, candidateId, request.projectId(), request.targetTest(),
                request.moduleRoot(), request.repositoryRoot(), request.repositoryRevision(),
                request.javaVersion(), request.adapterId(), request.adapterVersion(),
                request.input(), now));
        Optional<ContextId> previousId = caseCreated
                ? Optional.empty() : digestReader.read(caseId).latestContextId();
        ContextId contextId = candidateId;
        boolean contextChanged = true;
        if (previousId.isPresent()) {
            ContextSnapshot previous = repository.requireContext(caseId, previousId.orElseThrow());
            if (previous.completeness() == SnapshotCompleteness.COMPLETE
                    && candidate.completeness() == SnapshotCompleteness.COMPLETE
                    && previous.fingerprintSha256().equals(candidate.fingerprintSha256())) {
                contextId = previous.contextId();
                contextChanged = false;
            }
        }
        if (contextChanged) {
            repository.createContext(candidate);
        }

        AnalysisId analysisId = ids.newAnalysisId();
        repository.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, caseId, contextId,
                analysisId, request.question(), now));
        CaseDigest digest = digestReader.read(caseId);
        return new CaseOpenResult(
                caseId, contextId, analysisId, caseCreated, contextChanged, digest);
    }

    private static void validateExistingCase(CaseManifest manifest, CaseSessionRequest request) {
        if (!manifest.projectId().equals(request.projectId())) {
            throw new WorkspaceException(
                    "CASE_PROJECT_MISMATCH", "已有 Case 属于另一个项目");
        }
        if (!manifest.targetTest().equals(request.targetTest())) {
            throw new WorkspaceException(
                    "CASE_TARGET_TEST_MISMATCH", "已有 Case 属于另一个目标 UT");
        }
    }
}
