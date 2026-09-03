package org.example.algorithmdebug.contracts;

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
                "schemas", "trace", "method-path-summary-v4.schema.json");
        JsonSchemaTestSupport.assertValid(schema, MAPPER.writeValueAsString(summary));
    }
}
