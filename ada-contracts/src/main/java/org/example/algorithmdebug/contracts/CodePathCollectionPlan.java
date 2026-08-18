package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;

/**
 * CodePathTracer 的版本化、可归档采集计划。
 *
 * <p>{@code selectors} 是证据层最终允许保留的方法；当前外部工具只能按包采集，因此
 * {@code packagePrefixes} 与 {@code captureScope=PACKAGE_SUPERSET} 明确披露原始采集范围更宽。</p>
 */
public record CodePathCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        String sourceFingerprintSha256,
        List<MethodSelector> selectors,
        List<String> packagePrefixes,
        String captureScope,
        CollectionBudget budget,
        long estimatedPackageEvents,
        String rationale,
        Instant createdAt) {

    /** 校验身份、方法上限、包范围、估算和解释文本。 */
    public CodePathCollectionPlan {
        if (!SchemaVersions.CODEPATH_COLLECTION_PLAN.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 CodePathCollectionPlan schemaVersion");
        }
        planId = ContractChecks.requireNonNull(planId, "planId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        targetTest = ContractChecks.requireNonNull(targetTest, "targetTest");
        sourceFingerprintSha256 = ContractChecks.requireSha256(
                sourceFingerprintSha256, "sourceFingerprintSha256");
        selectors = ContractChecks.immutableList(selectors, "selectors");
        packagePrefixes = ContractChecks.immutableBoundedStrings(
                packagePrefixes, "packagePrefixes", 512);
        if (selectors.isEmpty() || selectors.size() > 200) {
            throw new IllegalArgumentException("selectors 数量必须在 1 到 200 之间");
        }
        if (packagePrefixes.isEmpty() || packagePrefixes.size() > 200
                || packagePrefixes.stream().anyMatch(value -> !value.matches(
                "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) {
            throw new IllegalArgumentException("packagePrefixes 非法");
        }
        if (!"PACKAGE_SUPERSET".equals(captureScope)) {
            throw new IllegalArgumentException("当前 CodePath 工具只支持 PACKAGE_SUPERSET");
        }
        budget = ContractChecks.requireNonNull(budget, "budget");
        if (estimatedPackageEvents < 0 || estimatedPackageEvents > 1_000_000) {
            throw new IllegalArgumentException("estimatedPackageEvents 超过安全预估上限");
        }
        rationale = ContractChecks.requireBoundedText(rationale, "rationale", 4_096, false);
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }
}
