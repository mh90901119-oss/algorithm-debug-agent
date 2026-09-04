package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlgorithmInputCaptureJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializedCaptureConformsToPublishedV1Schema() throws Exception {
        AnalysisId analysisId = new AnalysisId("analysis-1");
        AlgorithmInputCapture capture = new AlgorithmInputCapture(
                SchemaVersions.ALGORITHM_INPUT_CAPTURE,
                new CaseId("case-1"), analysisId,
                new TargetTest("fixture.AlgorithmTest", "runsAlgorithm"),
                "inputPath", "src/test/java/fixture/AlgorithmTest.java", 17,
                AlgorithmInputPathKind.RELATIVE, "case-input.json",
                AlgorithmInputComparison.FIRST_CAPTURE, Optional.empty(),
                new ArtifactReference(
                        "algorithm-input", "ALGORITHM_INPUT",
                        "input/case-input.json", "application/json",
                        "a".repeat(64), 128),
                Instant.parse("2026-08-27T00:00:00Z"));

        Path schema = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."),
                "schemas", "case", "algorithm-input-capture-v2.schema.json");
        JsonSchemaTestSupport.assertValid(schema, MAPPER.writeValueAsString(capture));
    }
}
