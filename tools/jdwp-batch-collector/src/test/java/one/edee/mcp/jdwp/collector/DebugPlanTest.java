package one.edee.mcp.jdwp.collector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugPlanTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void v5RequiresExactMethodDescriptorAndRejectsHistoricalSchemas() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().methodDescriptor = null;

        assertThrows(IllegalArgumentException.class, plan::validate);

        plan.schemaVersion = "3.0";
        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    void rejectsDuplicateProjectionEntries() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().capture.valuePaths = List.of("state", "state");

        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    void validatesFirstAndPeriodicMatchedHitSampling() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().maxObservedHits = 5;
        plan.tracepoints.getFirst().maxCapturedHits = 3;
        plan.tracepoints.getFirst().captureFirstMatchedHits = 1;
        plan.tracepoints.getFirst().captureEveryMatchedHits = 2;

        assertDoesNotThrow(plan::validate);

        plan.tracepoints.getFirst().captureFirstMatchedHits = 4;
        assertThrows(IllegalArgumentException.class, plan::validate);
        plan.tracepoints.getFirst().captureFirstMatchedHits = 0;
        plan.tracepoints.getFirst().captureEveryMatchedHits = 0;
        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    void rejectsMalformedTypedConditionBeforeAttaching() {
        DebugPlan plan = validPlan();
        DebugPlan.Condition condition = new DebugPlan.Condition();
        condition.valuePath = "waferNumber";
        condition.expectedType = "LONG";
        condition.expectedValue = "not-a-number";
        plan.tracepoints.getFirst().conditions.add(condition);

        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    void enforcesLoopbackEventAndUtf16CharBounds() {
        DebugPlan plan = validPlan();
        plan.target.host = "localhost";
        assertThrows(IllegalArgumentException.class, plan::validate);

        plan = validPlan();
        plan.maxEvents = 5_001;
        assertThrows(IllegalArgumentException.class, plan::validate);

        plan = validPlan();
        DebugPlan.Condition condition = new DebugPlan.Condition();
        condition.valuePath = "marker";
        condition.expectedType = "CHAR";
        condition.expectedValue = "\uD83D\uDE00";
        plan.tracepoints.getFirst().conditions.add(condition);
        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    void readsOnlyBoundedRegularPlanFiles() throws Exception {
        Path empty = temporaryDirectory.resolve("empty.json");
        Files.write(empty, new byte[0]);
        assertThrows(java.io.IOException.class,
                () -> CollectorMain.readPlan(new ObjectMapper(), empty));

        Path oversized = temporaryDirectory.resolve("oversized.json");
        Files.write(oversized, new byte[(int) CollectorMain.MAX_PLAN_BYTES + 1]);
        assertThrows(java.io.IOException.class,
                () -> CollectorMain.readPlan(new ObjectMapper(), oversized));
    }

    static DebugPlan validPlan() {
        DebugPlan plan = new DebugPlan();
        DebugPlan.Tracepoint tracepoint = new DebugPlan.Tracepoint();
        tracepoint.id = "point-1";
        tracepoint.className = "fixture.Algorithm";
        tracepoint.methodName = "solve";
        tracepoint.methodDescriptor = "()V";
        tracepoint.line = 12;
        plan.tracepoints.add(tracepoint);
        return plan;
    }
}
