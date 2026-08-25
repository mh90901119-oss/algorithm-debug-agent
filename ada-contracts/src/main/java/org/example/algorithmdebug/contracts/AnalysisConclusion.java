package org.example.algorithmdebug.contracts;

import java.util.List;

/**
 * 一条面向用户的分级结论，只保存陈述和来源引用，不保存模型内部推理过程。
 *
 * @param classification 结论来源等级
 * @param statement 面向用户的有界陈述
 * @param evidenceReferenceIds 支撑该陈述的 Evidence、Artifact 或事实 ID
 */
public record AnalysisConclusion(
        ClaimClassification classification,
        String statement,
        List<String> evidenceReferenceIds) {

    /** 校验分类、文本、引用和确认性结论的 provenance。 */
    public AnalysisConclusion {
        classification = ContractChecks.requireNonNull(classification, "classification");
        statement = ContractChecks.requireBoundedText(statement, "statement", 8_192, false);
        evidenceReferenceIds = ContractChecks.immutableNonBlankStrings(
                evidenceReferenceIds, "evidenceReferenceIds");
        if (evidenceReferenceIds.size() > 32) {
            throw new IllegalArgumentException("evidenceReferenceIds must not exceed 32 entries");
        }
        evidenceReferenceIds.forEach(value ->
                ContractChecks.requireOpaqueId(value, "evidenceReferenceId"));
        if ((classification == ClaimClassification.CONFIRMED_FACT
                || classification == ClaimClassification.VALIDATOR_CONCLUSION
                || classification == ClaimClassification.SOURCE_INFERENCE)
                && evidenceReferenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Confirmed facts, validator conclusions, and source inferences require evidence references");
        }
    }
}
