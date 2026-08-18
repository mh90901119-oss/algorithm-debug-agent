package org.example.algorithmdebug.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StaticAnalysisBudgetTest {

    @Test
    void defaultsAndHardLimitsMatchTheApprovedDesign() {
        StaticAnalysisBudget defaults = StaticAnalysisBudget.defaults();

        assertEquals(5_000, defaults.maxFiles());
        assertEquals(32L * 1024 * 1024, defaults.maxSourceBytes());
        assertEquals(64L * 1024 * 1024, defaults.maxCatalogBytes());
        new StaticAnalysisBudget(
                10_000, 64L * 1024 * 1024, 50_000, 250_000,
                128L * 1024 * 1024, 60_000);
        assertThrows(IllegalArgumentException.class, () ->
                new StaticAnalysisBudget(
                        10_001, 64L * 1024 * 1024, 1, 1,
                        16L * 1024 * 1024, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new StaticAnalysisBudget(
                        10_000, 64L * 1024 * 1024 + 1, 1, 1,
                        16L * 1024 * 1024, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new StaticAnalysisBudget(
                        1, 1, 1, 1, 16L * 1024 * 1024 - 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new StaticAnalysisBudget(
                        1, 1, 1, 1, 128L * 1024 * 1024 + 1, 1));
    }
}
