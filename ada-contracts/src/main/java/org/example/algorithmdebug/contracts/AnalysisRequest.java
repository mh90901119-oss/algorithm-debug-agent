package org.example.algorithmdebug.contracts;

import java.time.Instant;

/**
 * 一次用户问题或追问的不可变分析请求。
 *
 * @param schemaVersion Schema 版本
 * @param caseId 所属 Case
 * @param contextId 本次问题对应的 Context
 * @param analysisId Analysis ID
 * @param question 用户问题原文
 * @param createdAt 创建时间
 */
public record AnalysisRequest(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        String question,
        Instant createdAt) {

    /** 校验分析归属和问题大小。 */
    public AnalysisRequest {
        schemaVersion = CaseManifest.requireVersion(
                schemaVersion, SchemaVersions.ANALYSIS_REQUEST, "AnalysisRequest");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        question = ContractChecks.requireBoundedText(question, "question", 65_536, false);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
