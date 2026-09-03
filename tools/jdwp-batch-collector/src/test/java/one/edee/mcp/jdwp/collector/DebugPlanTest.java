package one.edee.mcp.jdwp.collector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DebugPlanTest {

    @Test
    void v4RequiresExactMethodDescriptorAndRejectsHistoricalSchemas() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().methodDescriptor = null;

        assertThrows(IllegalArgumentException.class, plan::validate);

        plan.schemaVersion = "3.0";
        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    void normalizesProjectionEntriesDeterministically() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().capture.locals = true;
        plan.tracepoints.getFirst().capture.localNames = new ArrayList<>(
            List.of(" state ", "state", "input")
        );

        plan.validate();

        assertEquals(List.of("state", "input"), plan.tracepoints.getFirst().capture.localNames);
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
        condition.localName = "waferNumber";
        condition.expectedType = "LONG";
        condition.expectedValue = "not-a-number";
        plan.tracepoints.getFirst().condition = condition;

        assertThrows(IllegalArgumentException.class, plan::validate);
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
