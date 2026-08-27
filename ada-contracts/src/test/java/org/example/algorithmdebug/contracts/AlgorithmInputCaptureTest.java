package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlgorithmInputCaptureTest {
    @Test
    void keepsOnlyPortableSourceIdentityAndRegisteredContentIdentity() {
        ArtifactReference artifact = new ArtifactReference(
                "analysis-1-algorithm-input", "ALGORITHM_INPUT",
                "analyses/analysis-1/input/algorithm-input.json", "application/json",
                "a".repeat(64), 42);
        AlgorithmInputCapture capture = new AlgorithmInputCapture(
                SchemaVersions.ALGORITHM_INPUT_CAPTURE,
                new CaseId("case-1"), new ContextId("context-1"), new AnalysisId("analysis-1"),
                new TargetTest("a.b.TargetTest", "runs"), "algorithmInput",
                "src/test/java/a/b/TargetTest.java", 8, AlgorithmInputPathKind.RELATIVE,
                "caseinput.json", AlgorithmInputComparison.FIRST_CAPTURE, Optional.empty(),
                artifact, Instant.parse("2026-08-27T00:00:00Z"));
        assertEquals("ALGORITHM_INPUT", capture.artifact().artifactType());
        assertEquals("caseinput.json", capture.fileName());
        assertThrows(IllegalArgumentException.class, () -> new AlgorithmInputCapture(
                SchemaVersions.ALGORITHM_INPUT_CAPTURE,
                capture.caseId(), capture.contextId(), capture.analysisId(), capture.targetTest(),
                capture.variableName(), "C:/private/TargetTest.java", capture.sourceLine(),
                capture.pathKind(), capture.fileName(), capture.comparison(),
                capture.previousAnalysisId(), artifact, capture.capturedAt()));
    }
}
