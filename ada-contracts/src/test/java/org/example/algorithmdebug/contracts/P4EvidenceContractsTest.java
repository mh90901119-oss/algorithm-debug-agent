package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class P4EvidenceContractsTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void rejectsNormalizationBudgetsBeyondHardLimits() {
        NormalizationBudget defaults = NormalizationBudget.defaults();

        assertEquals(50L * 1024 * 1024, NormalizationBudget.MAX_RAW_BYTES);
        assertTrue(defaults.maxRawBytes() < NormalizationBudget.MAX_RAW_BYTES);
        assertThrows(IllegalArgumentException.class, () -> new NormalizationBudget(
                NormalizationBudget.MAX_RAW_BYTES + 1,
                defaults.maxRecordBytes(), defaults.maxRecords(), defaults.maxMethods(),
                defaults.maxRelationships(), defaults.maxHits(), defaults.maxFramesPerHit(),
                defaults.maxValueFacts(), defaults.maxScalarChars(), defaults.maxSummaryBytes()));
    }

    @Test
    void rejectsCollectionUsedAsBothCurrentAndComparisonEvidence() {
        CollectionId duplicate = new CollectionId("collection-1");

        assertThrows(IllegalArgumentException.class, () -> new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"),
                List.of(duplicate), List.of(duplicate),
                Set.of(EvidenceDimension.TARGET_OUTCOME),
                512L * 1024, 1024L * 1024, NOW));
    }

    @Test
    void rejectsRequestWithoutAnyRequiredDimension() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceBuildRequest(
                SchemaVersions.EVIDENCE_BUILD_REQUEST,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                new RunId("run-1"),
                List.of(), List.of(), Set.of(),
                512L * 1024, 1024L * 1024, NOW));
    }

    @Test
    void targetFailureCanCoverOutcomeWithoutScheduleResult() {
        EvidenceFact targetFailure = new EvidenceFact(
                ClaimClassification.CONFIRMED_FACT,
                EvidenceDimension.TARGET_OUTCOME,
                "TARGET_TEST_ERROR",
                "目标 UT 抛出 java.lang.IllegalStateException",
                List.of(), Optional.empty());
        EvidenceBundle bundle = new EvidenceBundle(
                SchemaVersions.EVIDENCE_BUNDLE,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                List.of(targetFailure), List.of(),
                Set.of(EvidenceDimension.TARGET_OUTCOME, EvidenceDimension.VALIDATION),
                List.of(), false, NOW);

        assertTrue(bundle.coveredDimensions().contains(EvidenceDimension.TARGET_OUTCOME));
        assertFalse(bundle.coveredDimensions().contains(EvidenceDimension.SCHEDULE_RESULT));
    }

    @Test
    void jdwpProjectionPreservesCapturedAlgorithmValue() {
        TraceProvenance provenance = provenance(7, 9L);
        JdwpSnapshotSummary.ProjectionFact fact = new JdwpSnapshotSummary.ProjectionFact(
                "context.algorithmState", JdwpSnapshotSummary.ProjectionStatus.CAPTURED,
                Optional.of("STRING"), Optional.of("java.lang.String"),
                Optional.of("algorithm-input-value"), false,
                Optional.empty(), Optional.empty(), provenance);

        assertEquals("algorithm-input-value", fact.scalarValue().orElseThrow());
        assertEquals("context.algorithmState", fact.valuePath());
    }

    @Test
    void deterministicBundleRejectsModelOnlyClaimClassification() {
        EvidenceFact hypothesis = new EvidenceFact(
                ClaimClassification.LLM_HYPOTHESIS,
                EvidenceDimension.RUNTIME_STATE,
                "POSSIBLE_STATE", "可能的状态", List.of(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new EvidenceBundle(
                SchemaVersions.EVIDENCE_BUNDLE,
                new EvidenceId("evidence-1"), new CaseId("case-1"),
                new AnalysisId("analysis-1"),
                List.of(hypothesis), List.of(), Set.of(EvidenceDimension.RUNTIME_STATE),
                List.of(), false, NOW));
    }

    private static TraceProvenance provenance(long line, long sequence) {
        return new TraceProvenance(
                new CaseId("case-1"), new RunId("run-1"),
                new CollectionId("collection-1"),
                new ArtifactReference(
                        "raw-1", "JDWP_RAW_TRACE", "collections/collection-1/raw/jdwp.jsonl",
                        "application/x-ndjson", HASH, 128),
                line, Optional.empty(), Optional.of(sequence), "RAW_OBSERVATION");
    }
}
