package org.example.algorithmdebug.contracts;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一轮动态采集要回答的问题、待验证假设、来源证据和预期观察。
 *
 * <p>该契约只记录大模型的调查意图和证据关系，不判断假设的业务真实性。</p>
 *
 * @param questionToAnswer 本轮采集要回答的单一问题
 * @param hypothesis 本轮准备验证或拒绝的假设
 * @param basedOnEvidenceIds 触发本轮计划的同 Case 历史 Evidence
 * @param expectedObservations 能区分假设的预期运行时观察
 */
public record InvestigationIntent(
        String questionToAnswer,
        String hypothesis,
        List<EvidenceId> basedOnEvidenceIds,
        List<String> expectedObservations) {

    /** 校验文本和列表预算，并防止重复证据或观察。 */
    public InvestigationIntent {
        questionToAnswer = ContractChecks.requireBoundedText(
                questionToAnswer, "questionToAnswer", 2_048, false);
        hypothesis = ContractChecks.requireBoundedText(
                hypothesis, "hypothesis", 4_096, false);
        basedOnEvidenceIds = basedOnEvidenceIds == null
                ? List.of() : List.copyOf(basedOnEvidenceIds);
        if (basedOnEvidenceIds.size() > 20
                || basedOnEvidenceIds.stream().anyMatch(java.util.Objects::isNull)
                || new LinkedHashSet<>(basedOnEvidenceIds).size() != basedOnEvidenceIds.size()) {
            throw new IllegalArgumentException(
                    "basedOnEvidenceIds must contain at most 20 distinct Evidence IDs");
        }
        expectedObservations = expectedObservations == null
                ? List.of() : expectedObservations.stream()
                        .map(value -> ContractChecks.requireBoundedText(
                                value, "expectedObservation", 1_024, false))
                        .toList();
        if (expectedObservations.isEmpty() || expectedObservations.size() > 20
                || new LinkedHashSet<>(expectedObservations).size()
                        != expectedObservations.size()) {
            throw new IllegalArgumentException(
                    "expectedObservations must contain between 1 and 20 distinct entries");
        }
    }

    /** 为缺少结构化字段的历史 Plan 构造最小只读意图。 */
    public static InvestigationIntent legacy(String rationale) {
        String text = ContractChecks.requireBoundedText(
                rationale, "rationale", 4_096, false);
        return new InvestigationIntent(text, text, List.of(), List.of(text));
    }
}
