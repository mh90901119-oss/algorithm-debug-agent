package org.example.algorithmdebug.core;

import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.SnapshotCompleteness;

/** 面向模型的有界静态目录摘要；完整方法与边只通过 Artifact 读取。 */
public record StaticAnalysisSummary(
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        SnapshotCompleteness completeness,
        int methodCount,
        int edgeCount,
        int warningCount) {

    /** 校验身份和非负计数。 */
    public StaticAnalysisSummary {
        if (caseId == null || contextId == null || analysisId == null || completeness == null
                || methodCount < 0 || edgeCount < 0 || warningCount < 0) {
            throw new IllegalArgumentException("Static analysis summary is invalid");
        }
    }
}
