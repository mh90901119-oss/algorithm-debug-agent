package org.example.algorithmdebug.contracts.investigation;

import java.time.Instant;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.SchemaVersions;

/**
 * 采集前冻结、由确定性 evaluator 计算的结构化观测条件。
 *
 * @param schemaVersion Schema 版本
 * @param predicateId Predicate ID
 * @param caseId Case ID
 * @param analysisId Analysis ID
 * @param hypothesisId 目标假设
 * @param gapId 目标 Gap
 * @param operator 固定操作符
 * @param selector typed selector
 * @param role 关键或佐证角色
 * @param onTrue TRUE 时效果
 * @param onFalse FALSE 时效果
 * @param onUnknown UNKNOWN 时效果，必须为 NO_CHANGE
 * @param createdAt 冻结时间
 */
public record ObservationPredicate(
        String schemaVersion,
        ObservationPredicateId predicateId,
        CaseId caseId,
        AnalysisId analysisId,
        HypothesisId hypothesisId,
        EvidenceGapId gapId,
        ObservationOperator operator,
        ObservationSelector selector,
        PredicateRole role,
        HypothesisEffect onTrue,
        HypothesisEffect onFalse,
        HypothesisEffect onUnknown,
        Instant createdAt) {

    /** 校验操作符/selector 一致，并强制 UNKNOWN 不改变假设。 */
    public ObservationPredicate {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.OBSERVATION_PREDICATE, "ObservationPredicate");
        predicateId = InvestigationContractChecks.notNull(predicateId, "predicateId");
        caseId = InvestigationContractChecks.notNull(caseId, "caseId");
        analysisId = InvestigationContractChecks.notNull(analysisId, "analysisId");
        hypothesisId = InvestigationContractChecks.notNull(hypothesisId, "hypothesisId");
        gapId = InvestigationContractChecks.notNull(gapId, "gapId");
        operator = InvestigationContractChecks.notNull(operator, "operator");
        selector = InvestigationContractChecks.notNull(selector, "selector");
        role = InvestigationContractChecks.notNull(role, "role");
        onTrue = InvestigationContractChecks.notNull(onTrue, "onTrue");
        onFalse = InvestigationContractChecks.notNull(onFalse, "onFalse");
        onUnknown = InvestigationContractChecks.notNull(onUnknown, "onUnknown");
        createdAt = InvestigationContractChecks.notNull(createdAt, "createdAt");
        if (selector.operator() != operator) {
            throw new IllegalArgumentException("selector does not match operator");
        }
        if (onUnknown != HypothesisEffect.NO_CHANGE) {
            throw new IllegalArgumentException("onUnknown must be NO_CHANGE");
        }
    }
}
