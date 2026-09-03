package org.example.algorithmdebug.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.example.algorithmdebug.contracts.AnalysisId;
import org.example.algorithmdebug.contracts.CaseId;
import org.example.algorithmdebug.contracts.JdwpCaptureSpec;
import org.example.algorithmdebug.contracts.JdwpCollectionBudget;
import org.example.algorithmdebug.contracts.JdwpCollectionPlan;
import org.example.algorithmdebug.contracts.JdwpTracepointSpec;
import org.example.algorithmdebug.contracts.JdwpConditionOperator;
import org.example.algorithmdebug.contracts.JdwpScalarType;
import org.example.algorithmdebug.contracts.JdwpValueCondition;
import org.example.algorithmdebug.contracts.PlanId;
import org.example.algorithmdebug.contracts.SchemaVersions;
import org.example.algorithmdebug.contracts.SourceAnchor;
import org.example.algorithmdebug.contracts.TargetTest;
import org.junit.jupiter.api.Test;

class CollectorDebugPlanWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesExactCurrentCollectorShapeOnLoopback() throws Exception {
        byte[] json = new CollectorDebugPlanWriter().write(plan(), 51_337);
        JsonNode root = MAPPER.readTree(json);

        assertEquals("plan-1", root.path("sessionId").asText());
        assertEquals("127.0.0.1", root.path("target").path("host").asText());
        assertEquals(51_337, root.path("target").path("port").asInt());
        assertEquals(true, root.path("resumeOnAttach").asBoolean());
        assertEquals(120_000, root.path("idleTimeoutMillis").asLong());
        assertEquals(500, root.path("maxEvents").asInt());
        JsonNode point = root.path("tracepoints").path(0);
        assertEquals("4.0", root.path("schemaVersion").asText());
        assertEquals("fixture.Algorithm", point.path("className").asText());
        assertEquals("schedule", point.path("methodName").asText());
        assertEquals("()V", point.path("methodDescriptor").asText());
        assertEquals(11, point.path("line").asInt());
        assertFalse(point.path("capture").path("locals").asBoolean());
        assertFalse(root.toString().contains("sourceSha256"));
        assertEquals(0, point.path("capture").path("localNames").size());
        assertEquals(0, point.path("capture").path("fieldPaths").size());
        assertEquals(5, point.path("captureFirstMatchedHits").asInt());
        assertEquals(5, point.path("captureEveryMatchedHits").asInt());
    }

    @Test
    void outputIsDeterministicAndRejectsInvalidPort() {
        CollectorDebugPlanWriter writer = new CollectorDebugPlanWriter();

        assertArrayEquals(writer.write(plan(), 50_005), writer.write(plan(), 50_005));
        assertThrows(PlanCompilationException.class, () -> writer.write(plan(), 0));
        assertThrows(PlanCompilationException.class, () -> writer.write(plan(), 65_536));
    }

    @Test
    void canonicalizesTracepointOrderBeforeWritingCollectorJson() {
        CollectorDebugPlanWriter writer = new CollectorDebugPlanWriter();
        JdwpTracepointSpec first = point("a-point", 11);
        JdwpTracepointSpec second = point("z-point", 12);

        assertArrayEquals(
                writer.write(plan(List.of(second, first)), 50_005),
                writer.write(plan(List.of(first, second)), 50_005));
    }

    @Test
    void writesFirstAndPeriodicSamplingPolicy() throws Exception {
        SourceAnchor anchor = new SourceAnchor(
                "fixture.Algorithm", "schedule", "()V",
                "src/main/java/fixture/Algorithm.java", 10, 20);
        JdwpTracepointSpec point = new JdwpTracepointSpec(
                "point-1", "fixture.Algorithm#schedule()V", anchor, 11,
                100, 20, 5, 10, null, JdwpCaptureSpec.stackOnly());

        JsonNode json = MAPPER.readTree(new CollectorDebugPlanWriter().write(
                plan(List.of(point)), 50_005));

        assertEquals(5, json.path("tracepoints").path(0)
                .path("captureFirstMatchedHits").asInt());
        assertEquals(10, json.path("tracepoints").path(0)
                .path("captureEveryMatchedHits").asInt());
    }

    @Test
    void writesConditionalObservationAndCaptureBudgets() throws Exception {
        SourceAnchor anchor = new SourceAnchor(
                "fixture.Algorithm", "schedule", "()V",
                "src/main/java/fixture/Algorithm.java", 10, 20);
        JdwpTracepointSpec point = new JdwpTracepointSpec(
                "point-1", "fixture.Algorithm#schedule()V", anchor, 11,
                500, 20, 5, 10,
                new JdwpValueCondition(
                        "candidate", List.of("wafer", "id"),
                        JdwpConditionOperator.EQUALS, JdwpScalarType.STRING, "WAFER-1"),
                JdwpCaptureSpec.stackOnly());

        JsonNode json = MAPPER.readTree(new CollectorDebugPlanWriter().write(
                plan(List.of(point)), 50_005));
        JsonNode written = json.path("tracepoints").path(0);

        assertEquals(500, written.path("maxObservedHits").asInt());
        assertEquals(20, written.path("maxCapturedHits").asInt());
        assertEquals("candidate", written.path("condition").path("localName").asText());
        assertEquals("WAFER-1", written.path("condition").path("expectedValue").asText());
    }

    private static JdwpCollectionPlan plan() {
        return plan(List.of(point("point-1", 11)));
    }

    private static JdwpTracepointSpec point(String id, int line) {
        SourceAnchor anchor = new SourceAnchor(
                "fixture.Algorithm", "schedule", "()V",
                "src/main/java/fixture/Algorithm.java", 10, 20);
        return new JdwpTracepointSpec(
                id, "fixture.Algorithm#schedule()V", anchor, line,
                100, 20, 5, 5, null, JdwpCaptureSpec.stackOnly());
    }

    private static JdwpCollectionPlan plan(List<JdwpTracepointSpec> points) {
        return new JdwpCollectionPlan(
                SchemaVersions.JDWP_COLLECTION_PLAN,
                new PlanId("plan-1"), new CaseId("case-1"), new AnalysisId("analysis-1"), new TargetTest("fixture.AlgorithmTest", "runs"),
                points,
                JdwpCollectionBudget.defaults(), "Inspect the decision state",
                new org.example.algorithmdebug.contracts.InvestigationIntent(
                        "Which value selected the branch?", "The state selected the branch",
                        List.of(), List.of("Observed runtime state")),
                Instant.parse("2026-08-18T00:00:00Z"));
    }
}
