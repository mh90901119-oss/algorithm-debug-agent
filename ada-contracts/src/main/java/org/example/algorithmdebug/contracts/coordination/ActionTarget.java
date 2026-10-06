package org.example.algorithmdebug.contracts.coordination;

import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.RunId;

/**
 * 动作要访问的 Workspace 与可选归档对象。
 *
 * @param workspaceId 由 Runtime 解析的稳定 Workspace 标识，不是绝对路径
 * @param projectId 目标项目 ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param runId 可选 Run ID
 * @param planId 可选 Plan ID
 * @param evidenceId 可选 Evidence ID
 */
public record ActionTarget(
        String workspaceId,
        ProjectId projectId,
        CaseId caseId,
        AnalysisId analysisId,
        Optional<RunId> runId,
        Optional<PlanId> planId,
        Optional<EvidenceId> evidenceId) {

    /** 校验 Workspace、归属 ID 和可选目标。 */
    public ActionTarget {
        workspaceId = CoordinationContractChecks.requireText(
                workspaceId, "workspaceId", CoordinationLimits.MAX_WORKSPACE_ID_LENGTH, false);
        projectId = CoordinationContractChecks.requireNonNull(projectId, "projectId");
        caseId = CoordinationContractChecks.requireNonNull(caseId, "caseId");
        analysisId = CoordinationContractChecks.requireNonNull(analysisId, "analysisId");
        runId = CoordinationContractChecks.optional(runId, "runId");
        planId = CoordinationContractChecks.optional(planId, "planId");
        evidenceId = CoordinationContractChecks.optional(evidenceId, "evidenceId");
    }

    /**
     * 判断目标的项目、Case 和 Analysis 是否与请求身份完全一致。
     *
     * @param identity 请求身份
     * @return 三层身份均相同则为 true
     */
    public boolean belongsTo(AnalysisIdentity identity) {
        AnalysisIdentity checked = CoordinationContractChecks.requireNonNull(identity, "identity");
        return projectId.equals(checked.projectId())
                && caseId.equals(checked.caseId())
                && analysisId.equals(checked.analysisId());
    }
}
