package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;

/** CodePath 精确方法 Raw Trace 的通用方法统计和最近选中祖先摘要。 */
public record MethodPathSummary(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        ArtifactReference rawTrace,
        List<MethodStatistic> methods,
        List<ObservedPath> observedPaths,
        List<PathAnomaly> anomalies,
        boolean truncated,
        Instant createdAt) {

    /** 校验身份、精度和摘要硬上限。 */
    public MethodPathSummary {
        if (!SchemaVersions.METHOD_PATH_SUMMARY.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 MethodPathSummary schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        rawTrace = ContractChecks.requireNonNull(rawTrace, "rawTrace");
        methods = ContractChecks.immutableList(methods, "methods");
        observedPaths = ContractChecks.immutableList(observedPaths, "observedPaths");
        anomalies = ContractChecks.immutableList(anomalies, "anomalies");
        if (methods.size() > NormalizationBudget.MAX_METHODS
                || observedPaths.size() > NormalizationBudget.MAX_RELATIONSHIPS
                || anomalies.size() > 10_000) {
            throw new IllegalArgumentException("方法路径摘要超过硬上限");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    /** 一个计划方法的进入、退出和深度统计。 */
    public record MethodStatistic(
            String methodKey, long enterCount, long exitCount, int minDepth, int maxDepth,
            TraceProvenance firstObservation, TraceProvenance lastObservation) {
        public MethodStatistic {
            methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 2_048, false);
            if (enterCount < 0 || exitCount < 0 || minDepth < 0 || maxDepth < minDepth) {
                throw new IllegalArgumentException("方法统计非法");
            }
            firstObservation = ContractChecks.requireNonNull(firstObservation, "firstObservation");
            lastObservation = ContractChecks.requireNonNull(lastObservation, "lastObservation");
        }
    }

    /** 精确方法事件能够确认的最近选中祖先关系。 */
    public record ObservedPath(
            String ancestorMethodKey, String descendantMethodKey,
            String relationshipType, long count, TraceProvenance firstObservation) {
        public ObservedPath {
            ancestorMethodKey = ContractChecks.requireBoundedText(
                    ancestorMethodKey, "ancestorMethodKey", 2_048, false);
            descendantMethodKey = ContractChecks.requireBoundedText(
                    descendantMethodKey, "descendantMethodKey", 2_048, false);
            if (!"NEAREST_SELECTED_ANCESTOR".equals(relationshipType)) {
                throw new IllegalArgumentException(
                        "CodePath 精确方法 Trace 只能声明 NEAREST_SELECTED_ANCESTOR");
            }
            if (count < 1) throw new IllegalArgumentException("count 必须为正数");
            firstObservation = ContractChecks.requireNonNull(firstObservation, "firstObservation");
        }
    }

    /** 未配对、顺序或深度异常。 */
    public record PathAnomaly(String code, String detail, TraceProvenance provenance) {
        public PathAnomaly {
            code = ContractChecks.requireBoundedText(code, "code", 128, false);
            detail = ContractChecks.requireBoundedText(detail, "detail", 2_048, false);
            provenance = ContractChecks.requireNonNull(provenance, "provenance");
        }
    }
}
