package org.example.algorithmdebug.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.investigation.EvidenceGapId;
import org.example.algorithmdebug.contracts.investigation.HypothesisId;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicateId;

/**
 * 模型提交的调查绑定引用。这里只接受 ID；真实对象必须由编译器从当前状态解析。
 *
 * @param questionToAnswer 给人的有界说明，不参与机器判定
 * @param gapId 要消除的 Gap
 * @param hypothesisIds 本次区分的假设
 * @param predicateIds 采集前已冻结的 Predicate
 * @param basedOnEvidenceIds 本次规划所依据的既有 Evidence
 */
public record InvestigationBindingRequest(
        String questionToAnswer,
        EvidenceGapId gapId,
        List<HypothesisId> hypothesisIds,
        List<ObservationPredicateId> predicateIds,
        List<EvidenceId> basedOnEvidenceIds) {

    private static final int MAX_QUESTION_LENGTH = 2_048;

    /** 校验文本预算、数量预算和所有 ID 的唯一性。 */
    public InvestigationBindingRequest {
        questionToAnswer = Objects.requireNonNull(
                questionToAnswer, "questionToAnswer").strip();
        if (questionToAnswer.isEmpty() || questionToAnswer.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException(
                    "questionToAnswer must contain between 1 and "
                            + MAX_QUESTION_LENGTH + " characters");
        }
        gapId = Objects.requireNonNull(gapId, "gapId");
        hypothesisIds = immutableUnique(
                hypothesisIds, "hypothesisIds", InvestigationLimits.MAX_HYPOTHESES, true);
        predicateIds = immutableUnique(
                predicateIds, "predicateIds",
                InvestigationLimits.MAX_PREDICATES_PER_BINDING, true);
        basedOnEvidenceIds = immutableUnique(
                basedOnEvidenceIds, "basedOnEvidenceIds",
                InvestigationLimits.MAX_REFERENCES, false);
    }

    private static <T> List<T> immutableUnique(
            List<T> values, String field, int maximum, boolean required) {
        List<T> checked = List.copyOf(Objects.requireNonNull(values, field));
        if ((required && checked.isEmpty()) || checked.size() > maximum
                || checked.stream().anyMatch(Objects::isNull)
                || new HashSet<>(checked).size() != checked.size()) {
            throw new IllegalArgumentException(field + " is invalid or contains duplicate IDs");
        }
        return checked;
    }
}
