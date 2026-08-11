package org.example.algorithmdebug.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetTestTest {

    @Test
    void shouldCreateCanonicalJUnitSelector() {
        TargetTest target = new TargetTest(
                "org.example.scheduler.wafer.SimpleWaferSchedulerTest",
                "complexParallelModeSchedulesThreeJobsAcrossFiveChambers");

        assertEquals(
                "org.example.scheduler.wafer.SimpleWaferSchedulerTest#complexParallelModeSchedulesThreeJobsAcrossFiveChambers",
                target.selector());
    }

    @Test
    void shouldRejectInvalidClassOrMethodNames() {
        assertThrows(IllegalArgumentException.class, () -> new TargetTest("Simple Test", "case1"));
        assertThrows(IllegalArgumentException.class, () -> new TargetTest("a.ValidTest", "case-1"));
    }
}

