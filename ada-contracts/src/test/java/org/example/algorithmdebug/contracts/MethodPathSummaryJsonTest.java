package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MethodPathSummaryJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializedSummaryConformsToPublishedV2Schema() throws Exception {
        ArtifactReference raw = new ArtifactReference(
                "raw-1", "CODEPATH_RAW", "collections/c-1/raw/codepath.jsonl",
                "application/x-ndjson", "a".repeat(64), 128);
        TraceProvenance provenance = new TraceProvenance(
                new CaseId("case-1"), new RunId("run-1"),
                new CollectionId("collection-1"), raw, 1, Optional.of(1L),
                Optional.empty(), "RAW_OBSERVATION");
        MethodPathSummary summary = new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, new EvidenceId("evidence-1"),
                new CaseId("case-1"), new AnalysisId("analysis-1"),
                new RunId("run-1"), new PlanId("plan-1"), new CollectionId("collection-1"), raw,
                List.of(new MethodPathSummary.MethodStatistic(
                        "fixture.A#one()V", 1, 1, 0, 0, provenance, provenance)),
                List.of(new MethodPathSummary.ObservedPath(
                        "fixture.A#one()V", "fixture.B#two()V",
                        "NEAREST_SELECTED_ANCESTOR", 1, provenance)),
                List.of(new MethodPathSummary.PathAnomaly("UNBALANCED", "missing exit", provenance)),
                Optional.of(new MethodPathSummary.ScopeSummary(
                        "fixture.A#one()V", 1, 1, 0,
                        List.of(new MethodPathSummary.ScopeInvocation(
                                1, 1, Optional.of(2L), 2, 1,
                                Optional.of("PATH_001"), false)),
                        List.of(new MethodPathSummary.PathVariant(
                                "PATH_001", 1, List.of(1), List.of("fixture.A#one()V"))))),
                false, Instant.EPOCH);

        Path schema = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."),
                "schemas", "trace", "method-path-summary-v5.schema.json");
        JsonSchemaTestSupport.assertValid(schema, MAPPER.writeValueAsString(summary));
    }

    @Test
    void roundTripsAggregateCoverageAndTrackedValues() throws Exception {
        ArtifactReference raw = new ArtifactReference(
                "raw-aggregate", "CODEPATH_RAW_AGGREGATE",
                "collections/c1/raw/codepath-aggregate.json", "application/json",
                "a".repeat(64), 100);
        TraceProvenance provenance = new TraceProvenance(
                new CaseId("case-1"), new RunId("run-1"), new CollectionId("collection-1"),
                raw, 1, Optional.of(1L), Optional.empty(), "RAW_OBSERVATION");
        MethodPathSummary summary = new MethodPathSummary(
                SchemaVersions.METHOD_PATH_SUMMARY, new EvidenceId("evidence-1"),
                new CaseId("case-1"), new AnalysisId("analysis-1"), new RunId("run-1"),
                new PlanId("plan-1"), new CollectionId("collection-1"), raw,
                List.of(new MethodPathSummary.MethodStatistic(
                        "fixture.A#run()V", 2, 2, 0, 0, provenance, provenance)),
                List.of(), List.of(), Optional.empty(), CodePathCaptureMode.AGGREGATE,
                "SELECTED_METHOD_DEPTH", EvidenceSourceCoverage.PARTIAL,
                List.of("VALUE_CARDINALITY_LIMIT_REACHED"),
                List.of(new MethodPathSummary.ProjectionDistribution(
                        "fixture.A#run()V", "entity", "arg[0].id", 2, 1, true,
                        List.of(new MethodPathSummary.TrackedValueCount(
                                "VALUE", "STRING", Optional.of("E-1"), Optional.empty(), 1)))),
                true, Instant.EPOCH);

        MethodPathSummary decoded = MAPPER.readValue(MAPPER.writeValueAsBytes(summary), MethodPathSummary.class);

        assertEquals(summary, decoded);
        assertEquals(CodePathCaptureMode.AGGREGATE, decoded.captureMode());
    }
}
