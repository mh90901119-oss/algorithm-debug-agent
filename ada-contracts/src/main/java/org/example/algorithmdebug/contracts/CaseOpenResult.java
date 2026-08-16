package org.example.algorithmdebug.contracts;

/**
 * 创建或续接一次 Case 分析后返回给调用方的结构化结果。
 *
 * @param caseId Case ID
 * @param contextId 本次 Analysis 对应 Context
 * @param analysisId 新建 Analysis ID
 * @param caseCreated 是否创建了新 Case
 * @param contextChanged 相对旧 Case 最新 Context 是否发生变化；新 Case 为 true
 * @param digest 创建 Analysis 后重建的有界摘要
 */
public record CaseOpenResult(
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        boolean caseCreated,
        boolean contextChanged,
        CaseDigest digest) {

    /** 校验返回 ID 与 Digest 当前身份一致。 */
    public CaseOpenResult {
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        digest = ContractChecks.requireNonNull(digest, "digest");
        if (!caseId.equals(digest.caseId())
                || !contextId.equals(digest.latestContextId().orElse(null))
                || !analysisId.equals(digest.latestAnalysisId().orElse(null))) {
            throw new IllegalArgumentException("CaseOpenResult 与 Digest 身份不一致");
        }
    }
}
