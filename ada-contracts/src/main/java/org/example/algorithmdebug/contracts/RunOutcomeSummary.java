package org.example.algorithmdebug.contracts;

import java.util.List;
import java.util.Optional;

/**
 * 面向大模型的一次目标 UT 运行结构化摘要。
 * 各结果维度保持正交，详细原始内容通过 {@link ArtifactReference} 按需读取。
 */
public record RunOutcomeSummary(
        String schemaVersion,
        String eventType,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        boolean latestRunForAnalysis,
        ProcessOutcome processOutcome,
        TestOutcome testOutcome,
        GanttOutcome ganttOutcome,
        Optional<TargetFailureDiagnostic> targetFailure,
        Optional<AgentFailureDiagnostic> agentFailure,
        ComparisonOutcome comparisonOutcome,
        String comparisonSummary,
        List<ArtifactReference> artifacts) {

    /** 校验版本、事件类型、必填事实和不可变集合。 */
    public RunOutcomeSummary {
        schemaVersion = ContractChecks.requireNonBlank(schemaVersion, "schemaVersion");
        if (!SchemaVersions.RUN_OUTCOME_SUMMARY.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 RunOutcomeSummary schemaVersion: " + schemaVersion);
        }
        eventType = ContractChecks.requireNonBlank(eventType, "eventType");
        if (!"TARGET_TEST_RUN_COMPLETED".equals(eventType)) {
            throw new IllegalArgumentException("不支持的 eventType: " + eventType);
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        processOutcome = ContractChecks.requireNonNull(processOutcome, "processOutcome");
        testOutcome = ContractChecks.requireNonNull(testOutcome, "testOutcome");
        ganttOutcome = ContractChecks.requireNonNull(ganttOutcome, "ganttOutcome");
        targetFailure = ContractChecks.requireNonNull(targetFailure, "targetFailure");
        agentFailure = ContractChecks.requireNonNull(agentFailure, "agentFailure");
        comparisonOutcome = ContractChecks.requireNonNull(comparisonOutcome, "comparisonOutcome");
        comparisonSummary = ContractChecks.requireNonBlank(comparisonSummary, "comparisonSummary");
        artifacts = ContractChecks.immutableList(artifacts, "artifacts");
    }
}
