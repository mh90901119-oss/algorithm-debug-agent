package org.example.algorithmdebug.contracts;

/**
 * 创建或续接一次 Case 分析后返回给调用方的结构化结果。
 *
 * @param caseId Case ID
 * @param analysisId 新建 Analysis ID
 * @param caseCreated 是否创建了新 Case
 * @param resultJsonDirectory 当前项目已配置的算法 JSON 结果相对目录
 * @param digest 创建 Analysis 后重建的有界摘要
 */
public record CaseOpenResult(
        CaseId caseId,
        AnalysisId analysisId,
        boolean caseCreated,
        java.util.Optional<String> resultJsonDirectory,
        CaseDigest digest) {

    /** 校验返回 ID 与 Digest 当前身份一致。 */
    public CaseOpenResult {
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        resultJsonDirectory = ContractChecks.requireNonNull(
                resultJsonDirectory, "resultJsonDirectory");
        resultJsonDirectory = resultJsonDirectory.map(
                ProjectRegistration::validateResultJsonDirectory);
        digest = ContractChecks.requireNonNull(digest, "digest");
        if (!caseId.equals(digest.caseId())
                || !analysisId.equals(digest.latestAnalysisId().orElse(null))) {
            throw new IllegalArgumentException("CaseOpenResult does not match Digest identity");
        }
    }
}