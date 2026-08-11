package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutputStabilityWaiterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRequireTwoEquivalentChangedSnapshots() throws Exception {
        ScheduleResultSource source = new ScheduleResultSource(temporaryDirectory, false);
        OutputDirectorySnapshot before = snapshot(source, 0, 0);
        OutputDirectorySnapshot changed = snapshot(source, 10, 1);
        ArrayDeque<OutputDirectorySnapshot> observations = new ArrayDeque<>();
        observations.add(changed);
        observations.add(changed);
        AtomicLong nanos = new AtomicLong();
        OutputStabilityPolicy policy = new OutputStabilityPolicy(
                Duration.ofMillis(10), Duration.ofSeconds(1), 2);
        OutputStabilityWaiter waiter = new OutputStabilityWaiter(
                policy,
                ignored -> observations.removeFirst(),
                duration -> nanos.addAndGet(duration.toNanos()),
                nanos::get);

        assertEquals(changed, waiter.awaitStable(before, source));
    }

    @Test
    void shouldRejectContinuouslyChangingOutputAtTimeout() {
        ScheduleResultSource source = new ScheduleResultSource(temporaryDirectory, false);
        OutputDirectorySnapshot before = snapshot(source, 0, 0);
        AtomicLong sequence = new AtomicLong();
        AtomicLong nanos = new AtomicLong();
        OutputStabilityPolicy policy = new OutputStabilityPolicy(
                Duration.ofMillis(10), Duration.ofMillis(25), 2);
        OutputStabilityWaiter waiter = new OutputStabilityWaiter(
                policy,
                ignored -> {
                    long value = sequence.incrementAndGet();
                    return snapshot(source, value, value);
                },
                duration -> nanos.addAndGet(duration.toNanos()),
                nanos::get);

        HarnessException exception = assertThrows(
                HarnessException.class,
                () -> waiter.awaitStable(before, source));

        assertEquals("HARNESS_RESULT_NOT_STABLE", exception.code());
    }

    private static OutputDirectorySnapshot snapshot(
            ScheduleResultSource source, long size, long modified) {
        if (size == 0) {
            return new OutputDirectorySnapshot(source, Map.of());
        }
        Path relative = Path.of("result.json");
        return new OutputDirectorySnapshot(
                source,
                Map.of(relative, new OutputFileState(relative, size, modified)));
    }
}
