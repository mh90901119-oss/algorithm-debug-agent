package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.investigation.InvestigationBinding;

/** 面向模型的有界 CodePath 计划摘要；完整 selector 通过 Artifact 读取。 */
public record CodePathPlanSummary(
        CaseId caseId,
        AnalysisId analysisId,
        PlanId planId,
        int selectorCount,
        InvestigationBinding investigationBinding) {
    /** 校验身份和精确选择器数量。 */
    public CodePathPlanSummary {
        if (caseId == null || analysisId == null || planId == null
                || selectorCount < 1 || selectorCount > 50
                || investigationBinding == null
                || !caseId.equals(investigationBinding.caseId())
                || !analysisId.equals(investigationBinding.analysisId())) {
            throw new IllegalArgumentException("CodePath plan summary is invalid");
        }
    }
}
