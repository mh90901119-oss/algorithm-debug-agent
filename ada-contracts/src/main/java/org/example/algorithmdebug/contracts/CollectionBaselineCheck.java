package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.Optional;

/** 动态采集结果与无采集复现参考之间的确定性一致性检查。 */
public record CollectionBaselineCheck(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        CollectionId collectionId,
        ComparisonOutcome outcome,
        Optional<RunId> referenceRunId,
        Optional<String> currentGanttSha256,
        boolean evidenceUsable,
        String summary,
        Instant checkedAt) {

    /** 校验确认性证据只可能来自 MATCHED 检查。 */
    public CollectionBaselineCheck {
        if (!"1.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持的 CollectionBaselineCheck 版本");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        outcome = ContractChecks.requireNonNull(outcome, "outcome");
        referenceRunId = ContractChecks.requireNonNull(referenceRunId, "referenceRunId");
        currentGanttSha256 = ContractChecks.requireNonNull(currentGanttSha256, "currentGanttSha256")
                .map(value -> ContractChecks.requireSha256(value, "currentGanttSha256"));
        if (evidenceUsable && outcome != ComparisonOutcome.MATCHED) {
            throw new IllegalArgumentException("可用动态证据必须通过 MATCHED 检查");
        }
        summary = ContractChecks.requireBoundedText(summary, "summary", 2_048, false);
        checkedAt = ContractChecks.requireNonNull(checkedAt, "checkedAt");
    }
}
