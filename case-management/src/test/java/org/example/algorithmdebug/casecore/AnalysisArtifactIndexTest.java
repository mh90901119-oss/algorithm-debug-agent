package org.example.algorithmdebug.casecore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.ArtifactReference;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.CollectionId;
import org.example.algorithmdebug.contracts.EvidenceEligibility;
import org.example.algorithmdebug.contracts.EvidenceEligibilityReason;
import org.example.algorithmdebug.contracts.EvidenceId;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.ProjectId;
import org.example.algorithmdebug.contracts.RunId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.coordination.AnalysisIdentity;
import org.junit.jupiter.api.Test;

class AnalysisArtifactIndexTest {
    private static final AnalysisIdentity ANALYSIS_ONE = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-1"));
    private static final AnalysisIdentity ANALYSIS_TWO = new AnalysisIdentity(
            new ProjectId("project-1"), new CaseId("case-1"), new AnalysisId("analysis-2"));

    @Test
    void artifactIndexIsBoundedStableAndSortedByIdentity() {
        List<AnalysisArtifactIndex.Entry> source = new ArrayList<>(List.of(
                entry(ANALYSIS_TWO, "artifact-b"),
                entry(ANALYSIS_ONE, "artifact-c"),
                entry(ANALYSIS_ONE, "artifact-a")));
        AnalysisArtifactIndex first = AnalysisArtifactIndex.from(source);
        Collections.reverse(source);
        AnalysisArtifactIndex second = AnalysisArtifactIndex.from(source);

        assertEquals(first.entries(), second.entries());
        assertEquals(
                List.of("artifact-a", "artifact-c"),
                first.forAnalysis(ANALYSIS_ONE).stream()
                        .map(value -> value.artifact().artifactId()).toList());
    }

    @Test
    void duplicateArtifactIdentityIsRejected() {
        AnalysisArtifactIndex.Entry duplicate = entry(ANALYSIS_ONE, "artifact-a");

        assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisArtifactIndex.from(List.of(duplicate, duplicate)));
    }

    @Test
    void indexRejectsMoreThanItsNamedHardLimit() {
        List<AnalysisArtifactIndex.Entry> entries = new ArrayList<>();
        for (int index = 0; index <= AnalysisArtifactIndex.MAX_ENTRIES; index++) {
            entries.add(entry(ANALYSIS_ONE, "artifact-" + index));
        }

        assertThrows(IllegalArgumentException.class, () -> AnalysisArtifactIndex.from(entries));
    }

    @Test
    void collectionEligibilityDoesNotRequireAnEvidenceBundleIdentity() {
        AnalysisArtifactIndex.Entry collectionSummary = new AnalysisArtifactIndex.Entry(
                ANALYSIS_ONE,
                Optional.empty(),
                Optional.of(new PlanId("plan-1")),
                Optional.of(new CollectionId("collection-1")),
                Optional.empty(),
                new ArtifactReference(
                        "summary-1", "COLLECTION_EXECUTION_SUMMARY",
                        "collections/collection-1/collection-summary.json",
                        "application/json", "c".repeat(64), 64),
                Optional.of(new EvidenceEligibility(
                        SchemaVersions.EVIDENCE_ELIGIBILITY,
                        true, true, false, false, false, true, true,
                        List.of(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name()))));

        assertEquals(
                List.of(collectionSummary),
                AnalysisArtifactIndex.from(List.of(collectionSummary)).forAnalysis(ANALYSIS_ONE));
    }

    private static AnalysisArtifactIndex.Entry entry(
            AnalysisIdentity identity, String artifactId) {
        ArtifactReference artifact = new ArtifactReference(
                artifactId,
                "EVIDENCE_BUNDLE",
                "evidence/" + artifactId + ".json",
                "application/json",
                "a".repeat(64),
                128);
        EvidenceEligibility eligibility = new EvidenceEligibility(
                SchemaVersions.EVIDENCE_ELIGIBILITY,
                true, true, false, false, false, true, true,
                List.of(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name()));
        return new AnalysisArtifactIndex.Entry(
                identity,
                Optional.of(new RunId("run-1")),
                Optional.of(new PlanId("plan-1")),
                Optional.of(new CollectionId("collection-1")),
                Optional.of(new EvidenceId("evidence-1")),
                artifact,
                Optional.of(eligibility));
    }
}
