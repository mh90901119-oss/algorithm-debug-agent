package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.Optional;

/** 动态采集结果与无采集复现参考之间的确定性一致性检查。 */
public record CollectionBaselineCheck(
        String schemaVersion,
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        CollectionId collectionId,
        ComparisonOutcome outcome,
        Optional<RunId> referenceRunId,
        boolean evidenceUsable,
        String summary,
        Instant checkedAt) {

    /** 校验确认性证据只可能来自 MATCHED 检查。 */
    public CollectionBaselineCheck {
        if (!SchemaVersions.COLLECTION_BASELINE_CHECK.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported CollectionBaselineCheck version");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        outcome = ContractChecks.requireNonNull(outcome, "outcome");
        referenceRunId = ContractChecks.requireNonNull(referenceRunId, "referenceRunId");
        if (evidenceUsable && (outcome == ComparisonOutcome.CHANGED
                || outcome == ComparisonOutcome.INCOMPARABLE)) {
            throw new IllegalArgumentException("Changed or incomparable target failure evidence is not usable");
        }
        summary = ContractChecks.requireBoundedText(summary, "summary", 2_048, false);
        checkedAt = ContractChecks.requireNonNull(checkedAt, "checkedAt");
    }
}
