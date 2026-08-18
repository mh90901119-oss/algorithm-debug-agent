package org.example.algorithmdebug.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.ClaimClassification;
import org.example.algorithmdebug.contracts.ContextId;
import org.example.algorithmdebug.contracts.EvidenceBuildRequest;
import org.example.algorithmdebug.contracts.EvidenceBundle;
import org.example.algorithmdebug.contracts.EvidenceDimension;
import org.example.algorithmdebug.contracts.EvidenceFact;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SufficiencyStatus;

class EvidenceSufficiencyEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final EvidenceId EVIDENCE_ID = new EvidenceId("evidence-1");
    private static final CaseId CASE_ID = new CaseId("case-1");
    private static final ContextId CONTEXT_ID = new ContextId("context-1");
    private static final AnalysisId ANALYSIS_ID = new AnalysisId("analysis-1");

    @org.junit.jupiter.api.Test
    void sufficientOnlyWhenAllRequestedDimensionsAreCovered() {
        var evaluation = new EvidenceSufficiencyEvaluator().evaluate(
                request(Set.of(EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.VALIDATION)),
                bundle(Set.of(EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.VALIDATION),
                        List.of()));

        assertEquals(SufficiencyStatus.SUFFICIENT, evaluation.status());
        assertEquals(Set.of(), evaluation.missingDimensions());
    }

    @org.junit.jupiter.api.Test
    void missingRequestedDimensionIsInsufficient() {
        var evaluation = new EvidenceSufficiencyEvaluator().evaluate(
                request(Set.of(EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION)),
                bundle(Set.of(EvidenceDimension.VALIDATION), List.of()));

        assertEquals(SufficiencyStatus.INSUFFICIENT, evaluation.status());
        assertEquals(Set.of(EvidenceDimension.METHOD_PATH), evaluation.missingDimensions());
    }

    @org.junit.jupiter.api.Test
    void blockingCollectionContradictionWinsOverMissingDimensions() {
        EvidenceFact contradiction = new EvidenceFact(
                ClaimClassification.VALIDATOR_CONCLUSION, EvidenceDimension.VALIDATION,
                "COLLECTION_CONTRADICTED", "Baseline changed", List.of(), Optional.empty());

        var evaluation = new EvidenceSufficiencyEvaluator().evaluate(
                request(Set.of(EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION)),
                bundle(Set.of(), List.of(contradiction)));

        assertEquals(SufficiencyStatus.CONTRADICTED, evaluation.status());
        assertEquals(List.of("Baseline changed"), evaluation.contradictions());
    }

    private static EvidenceBuildRequest request(Set<EvidenceDimension> required) {
        return new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST, EVIDENCE_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, new org.example.algorithmdebug.contracts.RunId("run-1"),
                List.of(), List.of(), required, 64 * 1024, 256 * 1024, NOW);
    }

    private static EvidenceBundle bundle(
            Set<EvidenceDimension> covered, List<EvidenceFact> facts) {
        return new EvidenceBundle(
                SchemaVersions.EVIDENCE_BUNDLE, EVIDENCE_ID, CASE_ID, CONTEXT_ID,
                ANALYSIS_ID, facts, List.of(), covered, List.of(), false, NOW);
    }
}
