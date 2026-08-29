package org.example.algorithmdebug.harness;

import org.example.algorithmdebug.adapter.ScheduleResultSource;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 在有界时间内等待目标 UT 的结果目录元数据连续稳定。 */
public final class OutputStabilityWaiter {

    private final OutputStabilityPolicy policy;
    private final SnapshotProvider snapshotProvider;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;

    /** 使用真实目录快照与系统单调时钟创建 waiter。 */
    public OutputStabilityWaiter(
            OutputDirectorySnapshotter snapshotter,
            OutputStabilityPolicy policy) {
        this(policy, snapshotter::snapshot, duration -> Thread.sleep(duration), System::nanoTime);
    }

    OutputStabilityWaiter(
            OutputStabilityPolicy policy,
            SnapshotProvider snapshotProvider,
            Sleeper sleeper,
            LongSupplier nanoTime) {
        if (policy == null || snapshotProvider == null || sleeper == null || nanoTime == null) {
            throw new IllegalArgumentException("waiter dependencies must not be null");
        }
        this.policy = policy;
        this.snapshotProvider = snapshotProvider;
        this.sleeper = sleeper;
        this.nanoTime = nanoTime;
    }

    /**
     * @return 相对 before 有变化且连续满足稳定次数的最终快照
     * @throws HarnessException 无输出、持续变化、扫描失败或等待中断
     */
    public OutputDirectorySnapshot awaitStable(
            OutputDirectorySnapshot before,
            ScheduleResultSource source) throws HarnessException {
        if (before == null || source == null || !before.source().equals(source)) {
            throw new IllegalArgumentException("before and source must belong to the same result source");
        }
        long started = nanoTime.getAsLong();
        long timeoutNanos = policy.timeout().toNanos();
        OutputDirectorySnapshot previousChanged = null;
        int stableObservations = 0;
        boolean observedChange = false;
        while (nanoTime.getAsLong() - started <= timeoutNanos) {
            OutputDirectorySnapshot current = snapshotProvider.snapshot(source);
            boolean changed = !current.changedSince(before).isEmpty();
            if (changed) {
                observedChange = true;
                if (current.equals(previousChanged)) {
                    stableObservations++;
                } else {
                    previousChanged = current;
                    stableObservations = 1;
                }
                if (stableObservations >= policy.requiredStableObservations()) {
                    return current;
                }
            } else {
                previousChanged = null;
                stableObservations = 0;
            }
            sleep();
        }
        if (!observedChange) {
            throw new HarnessException(
                    "HARNESS_RESULT_NOT_PRODUCED",
                    "The target UT did not change the result directory within the stability polling budget");
        }
        throw new HarnessException(
                "HARNESS_RESULT_NOT_STABLE",
                "The target UT result directory kept changing throughout the stability polling budget");
    }

    private void sleep() throws HarnessException {
        try {
            sleeper.sleep(policy.pollInterval());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new HarnessException("HARNESS_RUN_INTERRUPTED", "Interrupted while waiting for result files to become stable", exception);
        }
    }

    @FunctionalInterface
    interface SnapshotProvider {
        OutputDirectorySnapshot snapshot(ScheduleResultSource source) throws HarnessException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
