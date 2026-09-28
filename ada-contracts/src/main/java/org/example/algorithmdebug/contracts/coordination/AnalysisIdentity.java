package org.example.algorithmdebug.contracts.coordination;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ProjectId;

/**
 * 一个分析闭环的稳定归属身份。
 *
 * @param projectId 目标项目 ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 */
public record AnalysisIdentity(ProjectId projectId, CaseId caseId, AnalysisId analysisId) {
    /** 拒绝缺少任一层归属的身份。 */
    public AnalysisIdentity {
        projectId = CoordinationContractChecks.requireNonNull(projectId, "projectId");
        caseId = CoordinationContractChecks.requireNonNull(caseId, "caseId");
        analysisId = CoordinationContractChecks.requireNonNull(analysisId, "analysisId");
    }
}
