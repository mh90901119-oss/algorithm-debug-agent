package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.PlanId;

/** 面向大模型的有界 JDWP 计划摘要；完整计划通过 Artifact 引用读取。 */
public record JdwpPlanSummary(
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        PlanId planId,
        int tracepointCount,
        int maximumEvents,
        long maximumBytes) {

    /** 校验摘要身份和预算计数。 */
    public JdwpPlanSummary {
        if (caseId == null || contextId == null || analysisId == null || planId == null) {
            throw new IllegalArgumentException("JDWP 计划摘要身份不能为空");
        }
        if (tracepointCount < 1 || maximumEvents < 1 || maximumBytes < 1) {
            throw new IllegalArgumentException("JDWP 计划摘要计数必须为正数");
        }
    }
}
