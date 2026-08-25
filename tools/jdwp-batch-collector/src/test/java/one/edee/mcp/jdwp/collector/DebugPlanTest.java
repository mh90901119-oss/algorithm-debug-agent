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
