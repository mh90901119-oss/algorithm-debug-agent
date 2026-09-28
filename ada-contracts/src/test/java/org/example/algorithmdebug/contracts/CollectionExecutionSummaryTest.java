package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CollectionExecutionSummaryTest {
    @Test
    void exposesBoundedArtifactIdsAndExplicitEligibility() {
        CollectionExecutionSummary summary = summary(eligible());

        assertEquals(List.of("summary-1"), summary.artifactIds());
        assertEquals(eligible(), summary.eligibility());
    }

    @Test
    void rejectsPrimaryArtifactThatWasNotRegistered() {
        assertThrows(IllegalArgumentException.class, () -> new CollectionExecutionSummary(
                SchemaVersions.COLLECTION_EXECUTION_SUMMARY,
                new CaseId("case-1"), new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "SUCCESS", ComparisonOutcome.NOT_COMPARED, eligible(),
                List.of("manifest.json"), List.of(), EvidenceSourceCoverage.COMPLETE,
                List.of(), Optional.of("summary-1"), "evidence_query",
                List.of(EvidenceQueryMode.SUMMARY), "Inspect the summary"));
    }

    @Test
    void defaultsOptionalModelGuidance() {
        CollectionExecutionSummary summary = new CollectionExecutionSummary(
                SchemaVersions.COLLECTION_EXECUTION_SUMMARY,
                new CaseId("case-1"), new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "SUCCESS", ComparisonOutcome.NOT_COMPARED, eligible(),
                List.of("manifest.json"), List.of(), null, null, null, null, null, null);

        assertEquals(EvidenceSourceCoverage.UNKNOWN, summary.sourceCoverage());
        assertEquals(List.of(), summary.reasonCodes());
        assertEquals(Optional.empty(), summary.primaryArtifactId());
        assertEquals(List.of(), summary.supportedModes());
        assertEquals("Inspect the returned collection facts before choosing another action",
                summary.nextAction());
    }

    private static CollectionExecutionSummary summary(EvidenceEligibility eligibility) {
        return new CollectionExecutionSummary(
                SchemaVersions.COLLECTION_EXECUTION_SUMMARY,
                new CaseId("case-1"), new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "SUCCESS", ComparisonOutcome.NOT_COMPARED, eligibility,
                List.of("manifest.json"), List.of("summary-1"),
                EvidenceSourceCoverage.COMPLETE, List.of(), Optional.of("summary-1"),
                "evidence_query", List.of(EvidenceQueryMode.SUMMARY), "Inspect the summary");
    }

    private static EvidenceEligibility eligible() {
        return new EvidenceEligibility(
                SchemaVersions.EVIDENCE_ELIGIBILITY,
                true, true, false, false, false, true, true,
                List.of(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name()));
    }
}
