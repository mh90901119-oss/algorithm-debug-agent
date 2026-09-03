package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JdwpValueConditionTest {
    @Test
    void acceptsOneBoundedValuePathAndTypedScalar() {
        JdwpValueCondition condition = new JdwpValueCondition(
                "candidate.wafer.id", JdwpConditionOperator.EQUALS,
                JdwpScalarType.STRING, "WAFER-1");

        assertEquals("candidate.wafer.id", condition.valuePath());
        assertEquals("WAFER-1", condition.expectedValue());
    }

    @Test
    void rejectsUnboundedOrMalformedConditions() {
        assertThrows(IllegalArgumentException.class, () -> new JdwpValueCondition(
                "candidate.a.b.c.d.e.f.g.h", JdwpConditionOperator.EQUALS,
                JdwpScalarType.STRING, "WAFER-1"));
        assertThrows(IllegalArgumentException.class, () -> new JdwpValueCondition(
                "candidate.getWafer()", JdwpConditionOperator.EQUALS,
                JdwpScalarType.STRING, "WAFER-1"));
        assertThrows(IllegalArgumentException.class, () -> new JdwpValueCondition(
                "candidate", JdwpConditionOperator.EQUALS,
                JdwpScalarType.LONG, "not-a-number"));
    }
}
