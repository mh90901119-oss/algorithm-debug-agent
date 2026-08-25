package org.example.algorithmdebug.casecore;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.ContextRecord;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 实现显式 Case 续接规则，追加 Context/Analysis，但绝不扫描或运行目标项目。 */
public final class CaseSessionService {

    private final CaseArchiveRepository repository;
    private final CaseDigestReader digestReader;
    private final OpaqueIdGenerator ids;
    private final Clock clock;

    /** 注入持久化、ID 与时间端口。 */
    public CaseSessionService(
            CaseArchiveRepository repository,
            CaseDigestReader digestReader,
            OpaqueIdGenerator ids,
            Clock clock) {
        if (repository == null || digestReader == null || ids == null || clock == null) {
            throw new IllegalArgumentException("CaseSessionService 依赖不能为空");
        }
        this.repository = repository;
        this.digestReader = digestReader;
        this.ids = ids;
        this.clock = clock;
    }

    /** 新建或续接 Case，并为本次问题创建 Analysis；该方法不访问目标 Workspace。 */
    public CaseOpenResult open(CaseSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        Instant now = clock.instant();
        boolean caseCreated = request.caseId().isEmpty();
        CaseId caseId = request.caseId().orElseGet(ids::newCaseId);
        if (caseCreated) {
            repository.createCase(new CaseManifest(
                    SchemaVersions.CASE_MANIFEST, caseId, request.projectId(),
                    request.targetTest(), request.adapterId(), request.question(), now));
        } else {
            validateExistingCase(repository.requireCase(caseId), request);
        }

        Optional<ContextId> previous = caseCreated
                ? Optional.empty() : digestReader.read(caseId).latestContextId();
        boolean contextCreated = caseCreated || request.contextMode() == ContextMode.CREATE_NEW;
        ContextId contextId;
        if (contextCreated) {
            contextId = ids.newContextId();
            repository.createContext(new ContextRecord(
                    SchemaVersions.CONTEXT_RECORD, caseId, contextId, now));
        } else {
            contextId = previous.orElseThrow(() -> new WorkspaceException(
                    "CONTEXT_NOT_FOUND", "已有 Case 没有可复用的 Context"));
        }

        AnalysisId analysisId = ids.newAnalysisId();
        repository.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, caseId, contextId,
                analysisId, request.question(), now));
        CaseDigest digest = digestReader.read(caseId);
        return new CaseOpenResult(
                caseId, contextId, analysisId, caseCreated, contextCreated,
                java.util.Optional.empty(), digest);
    }

    private static void validateExistingCase(CaseManifest manifest, CaseSessionRequest request) {
        if (!manifest.projectId().equals(request.projectId())) {
            throw new WorkspaceException("CASE_PROJECT_MISMATCH", "已有 Case 属于另一个项目");
        }
        if (!manifest.targetTest().equals(request.targetTest())) {
            throw new WorkspaceException("CASE_TARGET_TEST_MISMATCH", "已有 Case 属于另一个目标 UT");
        }
        if (!manifest.adapterId().equals(request.adapterId())) {
            throw new WorkspaceException("CASE_ADAPTER_MISMATCH", "已有 Case 属于另一个 Adapter");
        }
    }
}
