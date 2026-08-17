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
        validateTargetFailure(testOutcome, targetFailure, agentFailure);
        if (ganttOutcome == GanttOutcome.PRESENT
                && artifacts.stream().noneMatch(RunOutcomeSummary::isGanttArtifact)) {
            throw new IllegalArgumentException("GanttOutcome.PRESENT 必须包含 GANTT Artifact 引用");
        }
        if (ganttOutcome != GanttOutcome.PRESENT
                && artifacts.stream().anyMatch(RunOutcomeSummary::isGanttArtifact)) {
            throw new IllegalArgumentException(ganttOutcome + " 不得引用完整 GANTT Artifact");
        }
    }

    private static void validateTargetFailure(
            TestOutcome testOutcome,
            Optional<TargetFailureDiagnostic> targetFailure,
            Optional<AgentFailureDiagnostic> agentFailure) {
        if (testOutcome == TestOutcome.PASSED && targetFailure.isPresent()) {
            throw new IllegalArgumentException("PASSED 不得包含 targetFailure");
        }
        if ((testOutcome == TestOutcome.FAILED || testOutcome == TestOutcome.ERROR)
                && targetFailure.isEmpty()) {
            throw new IllegalArgumentException(testOutcome + " 必须包含 targetFailure");
        }
        if (testOutcome == TestOutcome.NOT_EXECUTED
                && targetFailure.isEmpty() && agentFailure.isEmpty()) {
            throw new IllegalArgumentException("NOT_EXECUTED 必须包含目标或 Agent 诊断");
        }
        if (targetFailure.isEmpty()) {
            return;
        }
        FailureCategory category = targetFailure.orElseThrow().category();
        if (testOutcome == TestOutcome.FAILED && category != FailureCategory.TEST_FAILURE) {
            throw new IllegalArgumentException("FAILED 只接受 TEST_FAILURE 分类");
        }
        if (testOutcome == TestOutcome.ERROR && category != FailureCategory.TEST_ERROR) {
            throw new IllegalArgumentException("ERROR 只接受 TEST_ERROR 分类");
        }
        if (testOutcome == TestOutcome.NOT_EXECUTED
                && category != FailureCategory.BUILD_FAILURE
                && category != FailureCategory.TEST_NOT_EXECUTED
                && category != FailureCategory.UNKNOWN) {
            throw new IllegalArgumentException("NOT_EXECUTED 的 targetFailure 分类不匹配");
        }
    }

    private static boolean isGanttArtifact(ArtifactReference artifact) {
        return "GANTT".equals(artifact.artifactType());
    }
}
