package one.edee.mcp.jdwp.collector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DebugPlanTest {

    @Test
    void v2RequiresExactMethodDescriptorWhileV1RemainsReadable() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().methodDescriptor = null;

        assertThrows(IllegalArgumentException.class, plan::validate);

        plan.schemaVersion = "1.0";
        assertDoesNotThrow(plan::validate);
    }

    @Test
    void normalizesProjectionEntriesDeterministically() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().capture.localNames = new ArrayList<>(
            List.of(" state ", "state", "input")
        );

        plan.validate();

        assertEquals(List.of("state", "input"), plan.tracepoints.getFirst().capture.localNames);
    }

    @Test
    void validatesSparseCaptureHitOrdinals() {
        DebugPlan plan = validPlan();
        plan.tracepoints.getFirst().maxHits = 5;
        plan.tracepoints.getFirst().captureOnHits = new ArrayList<>(List.of(1, 3, 5));

        assertDoesNotThrow(plan::validate);
        assertEquals(List.of(1, 3, 5), plan.tracepoints.getFirst().captureOnHits);

        plan.tracepoints.getFirst().captureOnHits = new ArrayList<>(List.of(1, 1));
        assertThrows(IllegalArgumentException.class, plan::validate);
        plan.tracepoints.getFirst().captureOnHits = new ArrayList<>(List.of(1, 6));
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
