package org.example.algorithmdebug.core.coordination;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.example.algorithmdebug.casecore.ConclusionDecisionArchive;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.ActionDecisionCode;
import org.example.algorithmdebug.contracts.coordination.AnalysisActionType;
import org.example.algorithmdebug.contracts.coordination.AnalysisControlView;
import org.example.algorithmdebug.contracts.coordination.ConclusionCandidate;
import org.example.algorithmdebug.contracts.coordination.ConclusionClaim;
import org.example.algorithmdebug.contracts.coordination.ConclusionDecision;
import org.example.algorithmdebug.contracts.coordination.ConclusionStatus;
import org.example.algorithmdebug.contracts.coordination.CoordinationErrorCode;
import org.example.algorithmdebug.contracts.coordination.CoordinationPolicyVersions;
import org.example.algorithmdebug.contracts.investigation.InvestigationState;
import org.example.algorithmdebug.evidence.CausalChainEligibilityEvaluator;

/**
 * 结论候选的唯一确定性门禁。
 *
 * <p>该组件先追加归档完整候选，再组合因果链、调查状态和控制状态的等级上限，最后追加唯一接受或拒绝终态。
 * 它不解释算法语义，也不调用模型。</p>
 */
public final class ConclusionGate {
    private static final String POLICY_VERSION = CoordinationPolicyVersions.CURRENT;
    private static final String IDENTITY_MISSING = "conclusion-identity";
    private static final String REVISION_MISSING = "control-revision";
    private static final String FINALIZE_NOT_ALLOWED = "analysis-finalize";

    private final CausalChainEligibilityEvaluator evaluator;
    private final CausalChainEligibilityEvaluator.ReferenceCatalog catalog;
    private final ConclusionDecisionArchive archive;
    private final Clock clock;

    /**
     * @param catalog 当前 Analysis 的只读 Evidence、Source Query 和 Artifact 目录
     * @param archive candidate-first 结论归档
     * @param clock 决策时间源
     */
    public ConclusionGate(
            CausalChainEligibilityEvaluator.ReferenceCatalog catalog,
            ConclusionDecisionArchive archive,
            Clock clock) {
        if (catalog == null || archive == null || clock == null) {
            throw new IllegalArgumentException("catalog, archive and clock must not be null");
        }
        this.evaluator = new CausalChainEligibilityEvaluator();
        this.catalog = catalog;
        this.archive = archive;
        this.clock = clock;
    }

    /** 校验候选并追加唯一结论决策；拒绝时返回可达到的最高等级和允许动作。 */
    public ConclusionDecision evaluate(
            ConclusionCandidate candidate,
            AnalysisControlView control,
            InvestigationState investigation) {
        if (candidate == null || control == null || investigation == null) {
            throw new IllegalArgumentException(
                    "candidate, control and investigation must not be null");
        }
        List<String> candidateProvenance = List.of(
                "analysis:" + candidate.identity().analysisId().value(),
                "revision:" + candidate.basedOnRevision());
        archive.appendCandidate(candidate, POLICY_VERSION, candidateProvenance, clock.instant());

        Assessment assessment = assess(candidate, control, investigation);
        boolean accepted = rank(candidate.requestedStatus()) <= rank(assessment.allowedStatus());
        ConclusionDecision decision = new ConclusionDecision(
                SchemaVersions.CONCLUSION_DECISION, POLICY_VERSION,
                candidate.conclusionId(), candidate.identity(), control.revision(),
                accepted ? ActionDecisionCode.ALLOWED : ActionDecisionCode.REJECTED,
                assessment.allowedStatus(),
                accepted ? List.of() : List.of(CoordinationErrorCode.CONCLUSION_NOT_ELIGIBLE),
                accepted ? List.of() : assessment.missingEvidence(),
                accepted ? List.of() : control.allowedActions());
        archive.appendDecision(
                decision,
                List.of(
                        "candidate:" + candidate.conclusionId(),
                        "investigation-sequence:" + investigation.lastSequence()),
                clock.instant());
        return decision;
    }

    private Assessment assess(
            ConclusionCandidate candidate,
            AnalysisControlView control,
            InvestigationState investigation) {
        ConclusionStatus allowed = ConclusionStatus.CONFIRMED;
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        LinkedHashSet<String> acceptedReferences = new LinkedHashSet<>();

        boolean identityMatches = candidate.identity().equals(control.identity())
                && candidate.identity().caseId().equals(investigation.caseId())
                && candidate.identity().analysisId().equals(investigation.analysisId());
        if (!identityMatches) {
            allowed = cap(allowed, ConclusionStatus.MISSING_EVIDENCE);
            missing.add(IDENTITY_MISSING);
        }
        if (candidate.basedOnRevision() != control.revision()) {
            allowed = cap(allowed, ConclusionStatus.MISSING_EVIDENCE);
            missing.add(REVISION_MISSING);
        }
        if (control.requestedAction() != AnalysisActionType.ANALYSIS_FINALIZE
                || control.decision() != ActionDecisionCode.ALLOWED) {
            allowed = cap(allowed, ConclusionStatus.MISSING_EVIDENCE);
            missing.add(FINALIZE_NOT_ALLOWED);
        }

        for (var chain : candidate.causalChains()) {
            var chainEvaluation = evaluator.evaluate(chain, investigation, catalog);
            allowed = cap(allowed, chainEvaluation.allowedStatus());
            missing.addAll(chainEvaluation.missingEvidence());
            acceptedReferences.addAll(chainEvaluation.acceptedReferenceIds());
        }
        allowed = cap(allowed, control.terminalEligibility());
        if (!control.remainingObligationIds().isEmpty()
                || !control.contradictionIds().isEmpty()
                || !control.openGapIds().isEmpty()
                || !control.unevaluatedPredicateIds().isEmpty()) {
            allowed = cap(allowed, ConclusionStatus.BOUNDED_HYPOTHESIS);
            missing.addAll(control.remainingObligationIds());
            missing.addAll(control.contradictionIds());
            missing.addAll(control.openGapIds());
            missing.addAll(control.unevaluatedPredicateIds());
        }
        if (!candidate.unresolvedGapIds().containsAll(control.openGapIds())) {
            allowed = cap(allowed, ConclusionStatus.BOUNDED_HYPOTHESIS);
            control.openGapIds().stream()
                    .filter(value -> !candidate.unresolvedGapIds().contains(value))
                    .forEach(missing::add);
        }
        if (!candidate.unresolvedGapIds().isEmpty()) {
            allowed = cap(allowed, ConclusionStatus.BOUNDED_HYPOTHESIS);
        }
        ClaimAssessment claims = assessClaims(candidate.claims(), acceptedReferences);
        allowed = cap(allowed, claims.allowedStatus());
        missing.addAll(claims.missingEvidence());
        return new Assessment(allowed, new ArrayList<>(missing));
    }

    private ClaimAssessment assessClaims(
            List<ConclusionClaim> claims, Set<String> acceptedReferences) {
        ConclusionStatus allowed = ConclusionStatus.CONFIRMED;
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        boolean hasDeterministicClaim = false;
        for (ConclusionClaim claim : claims) {
            List<String> invalidSourceReferences = claim.sourceReferenceIds().stream()
                    .filter(value -> !acceptedReferences.contains(value)).toList();
            if (!invalidSourceReferences.isEmpty() || claim.sourceReferenceIds().isEmpty()) {
                allowed = cap(allowed, ConclusionStatus.MISSING_EVIDENCE);
                if (claim.sourceReferenceIds().isEmpty()) {
                    missing.add(claim.claimId());
                } else {
                    missing.addAll(invalidSourceReferences);
                }
            }
            claim.evidenceIds().stream()
                    .filter(value -> !catalog.evidence().containsKey(value))
                    .map(value -> value.value()).forEach(value -> {
                        missing.add(value);
                    });
            boolean evidenceMissing = claim.evidenceIds().stream()
                    .anyMatch(value -> !catalog.evidence().containsKey(value));
            boolean artifactMissing = claim.artifactReferences().stream()
                    .map(value -> value.artifactId())
                    .anyMatch(value -> !catalog.artifactIds().contains(value));
            claim.artifactReferences().stream()
                    .map(value -> value.artifactId())
                    .filter(value -> !catalog.artifactIds().contains(value))
                    .forEach(missing::add);
            if (evidenceMissing || artifactMissing) {
                allowed = cap(allowed, ConclusionStatus.MISSING_EVIDENCE);
            }
            switch (claim.classification()) {
                case CONFIRMED_FACT, VALIDATOR_CONCLUSION -> hasDeterministicClaim = true;
                case SOURCE_INFERENCE, LLM_HYPOTHESIS -> { }
                case MISSING_EVIDENCE ->
                        allowed = cap(allowed, ConclusionStatus.MISSING_EVIDENCE);
            }
        }
        if (!hasDeterministicClaim) {
            allowed = cap(allowed, ConclusionStatus.BOUNDED_HYPOTHESIS);
        }
        return new ClaimAssessment(allowed, new ArrayList<>(missing));
    }

    private static ConclusionStatus cap(
            ConclusionStatus current, ConclusionStatus candidate) {
        return rank(candidate) < rank(current) ? candidate : current;
    }

    private static int rank(ConclusionStatus status) {
        return switch (status) {
            case MISSING_EVIDENCE -> 0;
            case BOUNDED_HYPOTHESIS -> 1;
            case CONFIRMED -> 2;
        };
    }

    private record Assessment(
            ConclusionStatus allowedStatus, List<String> missingEvidence) {
    }

    private record ClaimAssessment(
            ConclusionStatus allowedStatus, List<String> missingEvidence) {
    }
}
