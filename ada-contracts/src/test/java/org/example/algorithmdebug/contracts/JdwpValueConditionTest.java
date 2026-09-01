package org.example.algorithmdebug.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class JdwpValueConditionTest {

    @Test
    void acceptsOneBoundedLocalFieldPathAndTypedScalar() {
        JdwpValueCondition condition = new JdwpValueCondition(
                "candidate", List.of("wafer", "id"),
                JdwpConditionOperator.EQUALS, JdwpScalarType.STRING, "WAFER-1");

        assertEquals("candidate", condition.localName());
        assertEquals(List.of("wafer", "id"), condition.fieldPath());
        assertEquals("WAFER-1", condition.expectedValue());
    }

    @Test
    void rejectsUnboundedOrMalformedConditions() {
        assertThrows(IllegalArgumentException.class, () -> new JdwpValueCondition(
                "candidate", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"),
                JdwpConditionOperator.EQUALS, JdwpScalarType.STRING, "WAFER-1"));
        assertThrows(IllegalArgumentException.class, () -> new JdwpValueCondition(
                "candidate", List.of("getWafer()"),
                JdwpConditionOperator.EQUALS, JdwpScalarType.STRING, "WAFER-1"));
        assertThrows(IllegalArgumentException.class, () -> new JdwpValueCondition(
                "candidate", List.of(),
                JdwpConditionOperator.EQUALS, JdwpScalarType.LONG, "not-a-number"));
    }
}
