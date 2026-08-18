package org.example.algorithmdebug.core;

import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.PlanId;

/** 面向模型的有界 CodePath 计划摘要；完整 selector 只通过 Artifact 读取。 */
public record CodePathPlanSummary(
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        PlanId planId,
        int selectorCount,
        List<String> packagePrefixes,
        long estimatedPackageEvents) {

    /** 校验身份、计数和有界包列表。 */
    public CodePathPlanSummary {
        if (caseId == null || contextId == null || analysisId == null || planId == null
                || selectorCount < 1 || selectorCount > 200
                || estimatedPackageEvents < 0 || estimatedPackageEvents > 1_000_000) {
            throw new IllegalArgumentException("CodePath 计划摘要非法");
        }
        packagePrefixes = List.copyOf(packagePrefixes);
        if (packagePrefixes.isEmpty() || packagePrefixes.size() > 200) {
            throw new IllegalArgumentException("packagePrefixes 数量非法");
        }
    }
}
