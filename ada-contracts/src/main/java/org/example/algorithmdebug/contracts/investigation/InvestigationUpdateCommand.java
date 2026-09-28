package org.example.algorithmdebug.contracts.investigation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;

/** 模型唯一可提交的四种调查更新；不暴露 Evaluation 或状态设置。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "commandType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InvestigationUpdateCommand.AddHypothesis.class, name = "ADD_HYPOTHESIS"),
    @JsonSubTypes.Type(value = InvestigationUpdateCommand.AddEvidenceGap.class, name = "ADD_EVIDENCE_GAP"),
    @JsonSubTypes.Type(value = InvestigationUpdateCommand.RegisterPredicate.class, name = "REGISTER_PREDICATE"),
    @JsonSubTypes.Type(
            value = InvestigationUpdateCommand.MarkGapUnresolved.class,
            name = "MARK_GAP_UNRESOLVED")
})
public sealed interface InvestigationUpdateCommand permits
        InvestigationUpdateCommand.AddHypothesis,
        InvestigationUpdateCommand.AddEvidenceGap,
        InvestigationUpdateCommand.RegisterPredicate,
        InvestigationUpdateCommand.MarkGapUnresolved {

    /** @return 所属 Case ID */
    CaseId caseId();

    /** @return 所属 Analysis ID */
    AnalysisId analysisId();

    /** @return 允许模型提交的命令类型 */
    CommandType commandType();

    enum CommandType {
        ADD_HYPOTHESIS, ADD_EVIDENCE_GAP, REGISTER_PREDICATE, MARK_GAP_UNRESOLVED
    }

    record AddHypothesis(CaseId caseId, AnalysisId analysisId, HypothesisRecord hypothesis)
            implements InvestigationUpdateCommand {
        public AddHypothesis {
            validate(caseId, analysisId);
            hypothesis = InvestigationContractChecks.notNull(hypothesis, "hypothesis");
            requireIdentity(caseId, analysisId, hypothesis.caseId(), hypothesis.analysisId());
            if (hypothesis.status() != HypothesisStatus.OPEN) {
                throw new IllegalArgumentException("Model-created Hypothesis must be OPEN");
            }
        }
        @Override public CommandType commandType() { return CommandType.ADD_HYPOTHESIS; }
    }

    record AddEvidenceGap(CaseId caseId, AnalysisId analysisId, EvidenceGap gap)
            implements InvestigationUpdateCommand {
        public AddEvidenceGap {
            validate(caseId, analysisId);
            gap = InvestigationContractChecks.notNull(gap, "gap");
            requireIdentity(caseId, analysisId, gap.caseId(), gap.analysisId());
            if (gap.status() != EvidenceGapStatus.OPEN) {
                throw new IllegalArgumentException("Model-created EvidenceGap must be OPEN");
            }
        }
        @Override public CommandType commandType() { return CommandType.ADD_EVIDENCE_GAP; }
    }

    record RegisterPredicate(
            CaseId caseId, AnalysisId analysisId, ObservationPredicate predicate)
            implements InvestigationUpdateCommand {
        public RegisterPredicate {
            validate(caseId, analysisId);
            predicate = InvestigationContractChecks.notNull(predicate, "predicate");
            requireIdentity(caseId, analysisId, predicate.caseId(), predicate.analysisId());
        }
        @Override public CommandType commandType() { return CommandType.REGISTER_PREDICATE; }
    }

    record MarkGapUnresolved(
            CaseId caseId, AnalysisId analysisId, EvidenceGapId gapId, String reason)
            implements InvestigationUpdateCommand {
        public MarkGapUnresolved {
            validate(caseId, analysisId);
            gapId = InvestigationContractChecks.notNull(gapId, "gapId");
            reason = InvestigationContractChecks.text(
                    reason, "reason", InvestigationLimits.MAX_TEXT_LENGTH);
        }
        @Override public CommandType commandType() { return CommandType.MARK_GAP_UNRESOLVED; }
    }

    private static void validate(CaseId caseId, AnalysisId analysisId) {
        InvestigationContractChecks.notNull(caseId, "caseId");
        InvestigationContractChecks.notNull(analysisId, "analysisId");
    }

    private static void requireIdentity(
            CaseId expectedCase, AnalysisId expectedAnalysis,
            CaseId actualCase, AnalysisId actualAnalysis) {
        if (!expectedCase.equals(actualCase) || !expectedAnalysis.equals(actualAnalysis)) {
            throw new IllegalArgumentException("Investigation command identity mismatch");
        }
    }
}
