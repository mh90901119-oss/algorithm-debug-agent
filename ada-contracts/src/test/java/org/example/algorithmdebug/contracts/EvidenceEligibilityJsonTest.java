package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceEligibilityJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    @Test
    void currentSummaryAndEligibilityConformToPublishedSchemas() throws Exception {
        EvidenceEligibility eligibility = new EvidenceEligibility(
                SchemaVersions.EVIDENCE_ELIGIBILITY,
                true, true, false, false, false, true, true,
                List.of(EvidenceEligibilityReason.BASELINE_NOT_REQUIRED.name()));
        CollectionExecutionSummary summary = new CollectionExecutionSummary(
                SchemaVersions.COLLECTION_EXECUTION_SUMMARY,
                new CaseId("case-1"), new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"),
                "SUCCESS", ComparisonOutcome.NOT_COMPARED, eligibility,
                List.of("collections/collection-1/derived/summary.json"),
                List.of("summary-1"), EvidenceSourceCoverage.COMPLETE, List.of(),
                Optional.of("summary-1"), "evidence_query",
                List.of(EvidenceQueryMode.SUMMARY), "Inspect the summary");

        String eligibilityJson = MAPPER.writeValueAsString(eligibility);
        String summaryJson = MAPPER.writeValueAsString(summary);
        JsonSchemaTestSupport.assertValid(schema("evidence-eligibility-v1.schema.json"),
                eligibilityJson);
        JsonSchemaTestSupport.assertValid(schema("collection-execution-summary-v3.schema.json"),
                summaryJson);
        assertFalse(MAPPER.readTree(summaryJson).has("evidenceUsable"));
    }

    @Test
    void legacySummaryLoadsAsUnknownWithoutAutoUpgrade() throws Exception {
        String legacyJson = """
                {
                  "caseId":"case-1",
                  "analysisId":"analysis-1",
                  "runId":"run-1",
                  "planId":"plan-1",
                  "collectionId":"collection-1",
                  "completion":"SUCCESS",
                  "baselineOutcome":"NOT_COMPARED",
                  "evidenceUsable":true,
                  "artifactRelativePaths":["manifest.json"],
                  "artifactIds":[]
                }
                """;

        CollectionExecutionSummary decoded = MAPPER.readValue(
                legacyJson, CollectionExecutionSummary.class);

        assertEquals(SchemaVersions.COLLECTION_EXECUTION_SUMMARY_LEGACY,
                decoded.schemaVersion());
        assertFalse(decoded.eligibility().confirmationEligible());
        assertTrue(decoded.eligibility().reasonCodes().contains(
                EvidenceEligibilityReason.LEGACY_UNKNOWN.name()));
        JsonNode reserialized = MAPPER.readTree(MAPPER.writeValueAsBytes(decoded));
        assertEquals(SchemaVersions.COLLECTION_EXECUTION_SUMMARY_LEGACY,
                reserialized.path("schemaVersion").asText());
    }

    @Test
    void currentSummaryCannotOmitEligibility() {
        String malformedCurrentJson = """
                {
                  "schemaVersion":"3.0",
                  "caseId":"case-1",
                  "analysisId":"analysis-1",
                  "runId":"run-1",
                  "planId":"plan-1",
                  "collectionId":"collection-1",
                  "completion":"SUCCESS",
                  "baselineOutcome":"NOT_COMPARED",
                  "artifactRelativePaths":["manifest.json"],
                  "artifactIds":[]
                }
                """;

        org.junit.jupiter.api.Assertions.assertThrows(
                com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> MAPPER.readValue(malformedCurrentJson, CollectionExecutionSummary.class));
    }

    @Test
    void eligibilityRejectsReasonCodesThatContradictItsDimensions() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new EvidenceEligibility(
                        SchemaVersions.EVIDENCE_ELIGIBILITY,
                        true, true, false, false, false, true, true,
                        List.of(EvidenceEligibilityReason.ARTIFACT_UNREADABLE.name())));
    }

    @Test
    void embeddedEligibilityDefinitionMatchesStandaloneSchema() throws Exception {
        JsonNode standalone = MAPPER.readTree(
                java.nio.file.Files.readString(schema("evidence-eligibility-v1.schema.json")));
        JsonNode embedded = MAPPER.readTree(java.nio.file.Files.readString(
                        schema("collection-execution-summary-v3.schema.json")))
                .path("$defs").path("evidenceEligibility");

        assertEquals(standalone.path("required"), embedded.path("required"));
        assertEquals(standalone.path("properties"), embedded.path("properties"));
        assertEquals(standalone.path("additionalProperties"),
                embedded.path("additionalProperties"));
    }

    private static Path schema(String name) {
        return Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."),
                "schemas", "collection", name);
    }
}
