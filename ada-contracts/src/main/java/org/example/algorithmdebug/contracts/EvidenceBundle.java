package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 面向模型的小型证据目录，不内联完整 Raw Trace。 */
public record EvidenceBundle(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        AnalysisId analysisId,
        List<EvidenceFact> facts,
        List<EvidenceFact> comparisonFacts,
        Set<EvidenceDimension> coveredDimensions,
        List<ArtifactReference> artifacts,
        boolean truncated,
        Instant createdAt) {

    /** 校验身份、事实来源分类和有界集合。 */
    public EvidenceBundle {
        if (!SchemaVersions.EVIDENCE_BUNDLE.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported EvidenceBundle schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        facts = boundedFacts(facts, "facts");
        comparisonFacts = boundedFacts(comparisonFacts, "comparisonFacts");
        facts.forEach(EvidenceBundle::requireDeterministicClassification);
        comparisonFacts.forEach(EvidenceBundle::requireDeterministicClassification);
        coveredDimensions = EvidenceBuildRequest.immutableDimensions(
                coveredDimensions, "coveredDimensions");
        artifacts = ContractChecks.immutableList(artifacts, "artifacts");
        if (artifacts.size() > 128) {
            throw new IllegalArgumentException("artifacts must not exceed 128 entries");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    private static List<EvidenceFact> boundedFacts(List<EvidenceFact> values, String field) {
        List<EvidenceFact> copied = ContractChecks.immutableList(values, field);
        if (copied.size() > 1_024) {
            throw new IllegalArgumentException(field + " must not exceed 1024 entries");
        }
        return copied;
    }

    private static void requireDeterministicClassification(EvidenceFact fact) {
        if (fact.classification() == ClaimClassification.SOURCE_INFERENCE
                || fact.classification() == ClaimClassification.LLM_HYPOTHESIS) {
            throw new IllegalArgumentException("A deterministic P4 Bundle does not accept model-inferred classifications");
        }
    }
}
