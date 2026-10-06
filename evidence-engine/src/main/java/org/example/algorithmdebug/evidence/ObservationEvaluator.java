package org.example.algorithmdebug.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.investigation.EvidenceDisposition;
import org.example.algorithmdebug.contracts.investigation.HypothesisEffect;
import org.example.algorithmdebug.contracts.investigation.InvestigationLimits;
import org.example.algorithmdebug.contracts.investigation.InvestigationVersions;
import org.example.algorithmdebug.contracts.investigation.ObservationErrorCode;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluation;
import org.example.algorithmdebug.contracts.investigation.ObservationEvaluationId;
import org.example.algorithmdebug.contracts.investigation.ObservationPredicate;
import org.example.algorithmdebug.contracts.investigation.ObservationTruth;

/** 将冻结 Predicate 与 typed Evidence 对照，生成确定性三值 Evaluation。 */
public final class ObservationEvaluator {
    private static final String EVALUATION_ID_PREFIX = "evaluation-";
    private static final int EVALUATION_ID_HASH_LENGTH = 32;

    private final ObservationOperatorRegistry registry;

    /** 使用完整生产操作符注册表。 */
    public ObservationEvaluator() {
        this(new ObservationOperatorRegistry());
    }

    /** 注入不可变注册表，便于完整性测试。 */
    public ObservationEvaluator(ObservationOperatorRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
    }

    /**
     * 计算三值、证据等级和可应用效果。INVALID 在操作符分派前被短路。
     *
     * @param predicate 采集前冻结的 Predicate
     * @param evidence 已验证 typed Evidence
     * @return 可归档 Evaluation
     */
    public ObservationEvaluation evaluate(
            ObservationPredicate predicate, EvidenceView evidence) {
        requireIdentity(predicate, evidence);
        String inputHash = ObservationInputHasher.hash(predicate, evidence);
        EvidenceDisposition disposition = disposition(evidence);
        ObservationOperatorEvaluator.Result operatorResult =
                disposition == EvidenceDisposition.INVALID
                        ? new ObservationOperatorEvaluator.Result(
                                ObservationTruth.UNKNOWN,
                                List.of(ObservationErrorCode.OBSERVATION_EVIDENCE_INVALID.name()))
                        : registry.require(predicate.operator()).evaluate(
                                predicate.selector(), evidence);
        HypothesisEffect intended = ObservationEvaluationSemantics.intendedEffect(
                predicate, operatorResult.truth());
        boolean effectApplied = ObservationEvaluationSemantics.shouldApply(
                predicate, operatorResult.truth(), disposition);
        return new ObservationEvaluation(
                SchemaVersions.OBSERVATION_EVALUATION,
                new ObservationEvaluationId(EVALUATION_ID_PREFIX
                        + inputHash.substring(0, EVALUATION_ID_HASH_LENGTH)),
                predicate.caseId(), predicate.analysisId(), predicate.predicateId(),
                operatorResult.truth(), evidence.evidenceIds(), evidence.sourceCoverage(),
                disposition, effectApplied,
                effectApplied ? intended : HypothesisEffect.NO_CHANGE,
                limitations(evidence, disposition, operatorResult.limitations()),
                inputHash, evidence.observedAt(), InvestigationVersions.OBSERVATION_EVALUATOR);
    }

    private static EvidenceDisposition disposition(EvidenceView evidence) {
        if (!evidence.eligibility().artifactReadable()) {
            return EvidenceDisposition.INVALID;
        }
        return evidence.eligibility().confirmationEligible()
                ? EvidenceDisposition.CONFIRMATION_ELIGIBLE
                : EvidenceDisposition.CLUE_ONLY;
    }

    private static List<String> limitations(
            EvidenceView evidence,
            EvidenceDisposition disposition,
            List<String> operatorLimitations) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(operatorLimitations);
        values.addAll(evidence.limitations());
        if (disposition != EvidenceDisposition.CONFIRMATION_ELIGIBLE) {
            values.addAll(evidence.eligibility().reasonCodes());
        }
        ArrayList<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        if (sorted.size() > InvestigationLimits.MAX_LIMITATIONS) {
            throw new IllegalArgumentException("Observation limitations exceed the limit");
        }
        return List.copyOf(sorted);
    }

    private static void requireIdentity(
            ObservationPredicate predicate, EvidenceView evidence) {
        if (predicate == null || evidence == null) {
            throw new IllegalArgumentException("predicate and evidence must not be null");
        }
        if (!predicate.caseId().equals(evidence.caseId())
                || !predicate.analysisId().equals(evidence.analysisId())) {
            throw new IllegalArgumentException("Predicate and Evidence identity mismatch");
        }
    }
}
