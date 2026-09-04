package org.example.algorithmdebug.casecore;

import java.time.Clock;
import java.time.Instant;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.AnalysisRequest;
import org.example.algorithmdebug.contracts.CaseDigest;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CaseManifest;
import org.example.algorithmdebug.contracts.CaseOpenResult;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 实现显式 Case 续接规则并追加 Analysis，但绝不扫描或运行目标项目。 */
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
            throw new IllegalArgumentException("CaseSessionService dependencies must not be null");
        }
        this.repository = repository;
        this.digestReader = digestReader;
        this.ids = ids;
        this.clock = clock;
    }

    /** 新建或续接 Case，并为本次确定性调查创建 Analysis。 */
    public CaseOpenResult open(CaseSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
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

        AnalysisId analysisId = ids.newAnalysisId();
        repository.createAnalysis(new AnalysisRequest(
                SchemaVersions.ANALYSIS_REQUEST, caseId, analysisId, request.question(), now));
        CaseDigest digest = digestReader.read(caseId);
        return new CaseOpenResult(
                caseId, analysisId, caseCreated, java.util.Optional.empty(), digest);
    }

    private static void validateExistingCase(CaseManifest manifest, CaseSessionRequest request) {
        if (!manifest.projectId().equals(request.projectId())) {
            throw new WorkspaceException("CASE_PROJECT_MISMATCH", "The existing Case belongs to another project");
        }
        if (!manifest.targetTest().equals(request.targetTest())) {
            throw new WorkspaceException("CASE_TARGET_TEST_MISMATCH", "The existing Case belongs to another target UT");
        }
        if (!manifest.adapterId().equals(request.adapterId())) {
            throw new WorkspaceException("CASE_ADAPTER_MISMATCH", "The existing Case belongs to another Adapter");
        }
    }
}