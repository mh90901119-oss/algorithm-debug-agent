package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;

/** 追加式 Investigation Ledger 的 sealed 领域事件。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InvestigationEvent.ProblemFrameDefined.class, name = "PROBLEM_FRAME_DEFINED"),
    @JsonSubTypes.Type(value = InvestigationEvent.HypothesisAdded.class, name = "HYPOTHESIS_ADDED"),
    @JsonSubTypes.Type(value = InvestigationEvent.EvidenceGapAdded.class, name = "EVIDENCE_GAP_ADDED"),
    @JsonSubTypes.Type(value = InvestigationEvent.PredicateRegistered.class, name = "PREDICATE_REGISTERED"),
    @JsonSubTypes.Type(value = InvestigationEvent.PlanBound.class, name = "PLAN_BOUND"),
    @JsonSubTypes.Type(value = InvestigationEvent.ObservationEvaluated.class, name = "OBSERVATION_EVALUATED"),
    @JsonSubTypes.Type(value = InvestigationEvent.GapStatusChanged.class, name = "GAP_STATUS_CHANGED"),
    @JsonSubTypes.Type(
            value = InvestigationEvent.HypothesisStatusChanged.class,
            name = "HYPOTHESIS_STATUS_CHANGED")
})
public sealed interface InvestigationEvent permits
        InvestigationEvent.ProblemFrameDefined,
        InvestigationEvent.HypothesisAdded,
        InvestigationEvent.EvidenceGapAdded,
        InvestigationEvent.PredicateRegistered,
        InvestigationEvent.PlanBound,
        InvestigationEvent.ObservationEvaluated,
        InvestigationEvent.GapStatusChanged,
        InvestigationEvent.HypothesisStatusChanged {

    /** @return 事件 Schema 版本 */
    String schemaVersion();

    /** @return 事件唯一 ID */
    String eventId();

    /** @return 所属 Case ID */
    CaseId caseId();

    /** @return 所属 Analysis ID */
    AnalysisId analysisId();

    /** @return Ledger 内从一开始连续递增的序号 */
    long sequence();

    /** @return 事件发生时间 */
    Instant occurredAt();

    /** Problem Frame 首次定义事件。 */
    record ProblemFrameDefined(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, ProblemFrame problemFrame)
            implements InvestigationEvent {
        public ProblemFrameDefined {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            problemFrame = InvestigationContractChecks.notNull(problemFrame, "problemFrame");
            requireIdentity(caseId, analysisId, problemFrame.caseId(), problemFrame.analysisId());
        }
    }

    /** 模型提出新候选假设事件。 */
    record HypothesisAdded(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, HypothesisRecord hypothesis)
            implements InvestigationEvent {
        public HypothesisAdded {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            hypothesis = InvestigationContractChecks.notNull(hypothesis, "hypothesis");
            requireIdentity(caseId, analysisId, hypothesis.caseId(), hypothesis.analysisId());
            if (hypothesis.status() != HypothesisStatus.OPEN) {
                throw new IllegalArgumentException("A new Hypothesis must be OPEN");
            }
        }
    }

    /** 模型提出新 Evidence Gap 事件。 */
    record EvidenceGapAdded(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, EvidenceGap gap)
            implements InvestigationEvent {
        public EvidenceGapAdded {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            gap = InvestigationContractChecks.notNull(gap, "gap");
            requireIdentity(caseId, analysisId, gap.caseId(), gap.analysisId());
            if (gap.status() != EvidenceGapStatus.OPEN) {
                throw new IllegalArgumentException("A new EvidenceGap must be OPEN");
            }
        }
    }

    /** 采集前冻结 Predicate 事件。 */
    record PredicateRegistered(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, ObservationPredicate predicate)
            implements InvestigationEvent {
        public PredicateRegistered {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            predicate = InvestigationContractChecks.notNull(predicate, "predicate");
            requireIdentity(caseId, analysisId, predicate.caseId(), predicate.analysisId());
        }
    }

    /** Plan 成功归档后由系统追加的绑定事件。 */
    record PlanBound(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, PlanId planId, InvestigationBinding binding)
            implements InvestigationEvent {
        public PlanBound {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            planId = InvestigationContractChecks.notNull(planId, "planId");
            binding = InvestigationContractChecks.notNull(binding, "binding");
            requireIdentity(caseId, analysisId, binding.caseId(), binding.analysisId());
        }
    }

    /** Collector 后处理完成后由系统追加的 Evaluation 事件。 */
    record ObservationEvaluated(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, ObservationEvaluation evaluation)
            implements InvestigationEvent {
        public ObservationEvaluated {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            evaluation = InvestigationContractChecks.notNull(evaluation, "evaluation");
            requireIdentity(caseId, analysisId, evaluation.caseId(), evaluation.analysisId());
        }
    }

    /** Gap 状态由 Plan/Evaluation 或显式 unresolved 命令驱动的系统事件。 */
    record GapStatusChanged(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, EvidenceGapId gapId,
            EvidenceGapStatus previousStatus, EvidenceGapStatus newStatus)
            implements InvestigationEvent {
        public GapStatusChanged {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            gapId = InvestigationContractChecks.notNull(gapId, "gapId");
            previousStatus = InvestigationContractChecks.notNull(previousStatus, "previousStatus");
            newStatus = InvestigationContractChecks.notNull(newStatus, "newStatus");
            if (previousStatus == newStatus) {
                throw new IllegalArgumentException("Gap status transition must change status");
            }
        }
    }

    /** reducer 计算后的假设状态转换事件。 */
    record HypothesisStatusChanged(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt, HypothesisId hypothesisId,
            HypothesisStatus previousStatus, HypothesisStatus newStatus,
            HypothesisEvaluation evaluation)
            implements InvestigationEvent {
        public HypothesisStatusChanged {
            validate(schemaVersion, eventId, caseId, analysisId, sequence, occurredAt);
            hypothesisId = InvestigationContractChecks.notNull(hypothesisId, "hypothesisId");
            previousStatus = InvestigationContractChecks.notNull(previousStatus, "previousStatus");
            newStatus = InvestigationContractChecks.notNull(newStatus, "newStatus");
            evaluation = InvestigationContractChecks.notNull(evaluation, "evaluation");
            if (!hypothesisId.equals(evaluation.hypothesisId())
                    || previousStatus != evaluation.previousStatus()
                    || newStatus != evaluation.newStatus()) {
                throw new IllegalArgumentException("Hypothesis transition does not match evaluation");
            }
        }
    }

    private static void validate(
            String schemaVersion, String eventId, CaseId caseId, AnalysisId analysisId,
            long sequence, Instant occurredAt) {
        InvestigationContractChecks.version(
                schemaVersion, SchemaVersions.INVESTIGATION_EVENT, "InvestigationEvent");
        InvestigationContractChecks.id(eventId, "eventId");
        InvestigationContractChecks.notNull(caseId, "caseId");
        InvestigationContractChecks.notNull(analysisId, "analysisId");
        InvestigationContractChecks.notNull(occurredAt, "occurredAt");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }

    private static void requireIdentity(
            CaseId expectedCase, AnalysisId expectedAnalysis,
            CaseId actualCase, AnalysisId actualAnalysis) {
        if (!expectedCase.equals(actualCase) || !expectedAnalysis.equals(actualAnalysis)) {
            throw new IllegalArgumentException("Investigation event identity mismatch");
        }
    }
}
