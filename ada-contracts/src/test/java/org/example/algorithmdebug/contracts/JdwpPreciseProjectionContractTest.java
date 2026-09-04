package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class JdwpPreciseProjectionContractTest {

    @Test
    void acceptsExactValuePathsAndBoundedAndConditions() {
        JdwpCaptureSpec capture = new JdwpCaptureSpec(
                true, 8, 256,
                List.of("context.wafer.id", "decision.start"));
        JdwpValueCondition first = new JdwpValueCondition(
                "context.job.id", JdwpConditionOperator.EQUALS,
                JdwpScalarType.STRING, "JOB-1");
        JdwpValueCondition second = new JdwpValueCondition(
                "context.wafer.id", JdwpConditionOperator.EQUALS,
                JdwpScalarType.STRING, "WAFER-1");

        JdwpTracepointSpec point = new JdwpTracepointSpec(
                "point-1", "fixture.Algorithm#schedule()V",
                new SourceAnchor("fixture.Algorithm", "schedule", "()V",
                        "src/main/java/fixture/Algorithm.java", 10, 20),
                12, 100, 10, 5, 10, List.of(first, second), capture);

        assertEquals(List.of("context.wafer.id", "decision.start"),
                point.capture().valuePaths());
        assertEquals(List.of(first, second), point.conditions());
    }

    @Test
    void rejectsUnsafePathsAndMoreThanFourConditions() {
        assertThrows(IllegalArgumentException.class, () -> new JdwpCaptureSpec(
                true, 8, 256, List.of("context.getWafer()")));
        assertThrows(IllegalArgumentException.class, () -> new JdwpCaptureSpec(
                true, 8, 256, List.of("a.b.c.d.e.f.g.h.i")));

        JdwpValueCondition condition = new JdwpValueCondition(
                "context.wafer.id", JdwpConditionOperator.EQUALS,
                JdwpScalarType.STRING, "WAFER-1");
        assertThrows(IllegalArgumentException.class, () -> new JdwpTracepointSpec(
                "point-1", "fixture.Algorithm#schedule()V",
                new SourceAnchor("fixture.Algorithm", "schedule", "()V",
                        "src/main/java/fixture/Algorithm.java", 10, 20),
                12, 100, 10, 5, 10,
                List.of(condition, condition, condition, condition, condition),
                JdwpCaptureSpec.stackOnly()));
    }
}
