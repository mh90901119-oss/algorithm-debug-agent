package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class P4TraceContractsTest {

    private static final String HASH = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void methodPathRelationCannotClaimDirectCall() {
        assertThrows(IllegalArgumentException.class, () -> new MethodPathSummary.ObservedPath(
                "a.A#one()V", "a.A#two()V", "DIRECT_CALL", 1, provenance()));
    }

    @Test
    void failedNormalizationHasNoSummaryArtifactAndCarriesFailureCode() {
        NormalizationManifest manifest = new NormalizationManifest(
                SchemaVersions.NORMALIZATION_MANIFEST,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"), new PlanId("plan-1"),
                new CollectionId("collection-1"), "CODEPATH",
                "method-path-normalizer", "1.0", NormalizationStatus.FAILED,
                rawArtifact(), Optional.empty(), NormalizationBudget.defaults(),
                2, 0, List.of(), Optional.of("NORMALIZE_JSON_INVALID"),
                "line 2 is not valid JSON", NOW);

        assertTrue(manifest.summaryArtifact().isEmpty());
        assertEquals("NORMALIZE_JSON_INVALID", manifest.failureCode().orElseThrow());
    }

    @Test
    void completeNormalizationRequiresSummaryArtifact() {
        assertThrows(IllegalArgumentException.class, () -> new NormalizationManifest(
                SchemaVersions.NORMALIZATION_MANIFEST,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"), new PlanId("plan-1"),
                new CollectionId("collection-1"), "JDWP",
                "jdwp-snapshot-normalizer", "1.0", NormalizationStatus.COMPLETE,
                rawArtifact(), Optional.empty(), NormalizationBudget.defaults(),
                1, 1, List.of(), Optional.empty(), "", NOW));
    }

    @Test
    void validCollectionCanCoverRuntimeDimension() {
        CollectionValidation validation = new CollectionValidation(
                SchemaVersions.COLLECTION_VALIDATION,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"), new PlanId("plan-1"),
                new CollectionId("collection-1"), "JDWP",
                EvidenceValidationStatus.VALID, List.of(),
                Set.of(EvidenceDimension.RUNTIME_STATE, EvidenceDimension.VALIDATION),
                Optional.of(summaryArtifact()), NOW);

        assertTrue(validation.coveredDimensions().contains(EvidenceDimension.RUNTIME_STATE));
    }

    @Test
    void nonValidCollectionCannotClaimDynamicCoverage() {
        assertThrows(IllegalArgumentException.class, () -> new CollectionValidation(
                SchemaVersions.COLLECTION_VALIDATION,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"), new PlanId("plan-1"),
                new CollectionId("collection-1"), "CODEPATH",
                EvidenceValidationStatus.INCONCLUSIVE,
                List.of(new ValidationFinding(
                        "TRACE_TRUNCATED", EvidenceValidationStatus.INCONCLUSIVE,
                        "raw trace was truncated", List.of(rawArtifact()), Optional.empty())),
                Set.of(EvidenceDimension.METHOD_PATH, EvidenceDimension.VALIDATION),
                Optional.of(summaryArtifact()), NOW));
    }

    @Test
    void sufficiencyKeepsMissingScheduleResultSeparateFromTargetOutcome() {
        SufficiencyEvaluation evaluation = new SufficiencyEvaluation(
                SchemaVersions.SUFFICIENCY_EVALUATION,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                SufficiencyStatus.INSUFFICIENT,
                Set.of(EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.SCHEDULE_RESULT,
                        EvidenceDimension.VALIDATION),
                Set.of(EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.VALIDATION),
                Set.of(EvidenceDimension.SCHEDULE_RESULT), List.of(), NOW);

        assertEquals(Set.of(EvidenceDimension.SCHEDULE_RESULT), evaluation.missingDimensions());
    }

    @Test
    void sufficiencyRejectsEmptyRequiredDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new SufficiencyEvaluation(
                SchemaVersions.SUFFICIENCY_EVALUATION,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                SufficiencyStatus.SUFFICIENT, Set.of(), Set.of(), Set.of(), List.of(), NOW));
    }

    private static TraceProvenance provenance() {
        return new TraceProvenance(
                new CaseId("case-1"), new RunId("run-1"),
                new CollectionId("collection-1"), rawArtifact(), 1,
                Optional.of(1L), Optional.empty(), "RAW_OBSERVATION");
    }

    private static ArtifactReference rawArtifact() {
        return new ArtifactReference(
                "raw-1", "CODEPATH_FILTERED_TRACE",
                "collections/collection-1/raw/filtered.jsonl",
                "application/x-ndjson", HASH, 200);
    }

    private static ArtifactReference summaryArtifact() {
        return new ArtifactReference(
                "summary-1", "RUNTIME_SUMMARY",
                "collections/collection-1/derived/evidence-1/summary.json",
                "application/json", HASH, 300);
    }
}
