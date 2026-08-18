package org.example.algorithmdebug.codepath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodePathProcessCollectorPolicyTest {

    @Test
    void reportsNoMatchPrecisionWhenNoPlannedMethodEventWasRetained() {
        MethodPathFilterResult empty = new MethodPathFilterResult(
                12, 0, 1_024, 0, "a".repeat(64),
                0, 0, false, Optional.empty());

        assertEquals("NONE", CodePathProcessCollector.matchPrecision(empty));
    }

    @Test
    void distinguishesExactAndClassMethodSupersetPrecision() {
        MethodPathFilterResult exact = new MethodPathFilterResult(
                2, 2, 200, 100, "a".repeat(64),
                2, 0, false, Optional.empty());
        MethodPathFilterResult degraded = new MethodPathFilterResult(
                2, 2, 200, 100, "b".repeat(64),
                1, 1, false, Optional.empty());

        assertEquals("EXACT_DESCRIPTOR", CodePathProcessCollector.matchPrecision(exact));
        assertEquals("CLASS_METHOD_SUPERSET", CodePathProcessCollector.matchPrecision(degraded));
    }
}
