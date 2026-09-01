package org.example.algorithmdebug.harness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 等待外部进程，并在超时或中断时幂等清理它的完整后代树。 */
public final class ProcessSupervisor {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    /**
     * @return 正常退出或超时后的结构化监管结果
     * @throws HarnessException 中断或进程树未能清理
     */
    SupervisionResult await(Process process, Duration timeout, ProcessLimits limits)
            throws HarnessException {
        if (process == null || timeout == null || timeout.isZero() || timeout.isNegative() || limits == null) {
            throw new IllegalArgumentException("process, positive timeout, and limits must not be null");
        }
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new SupervisionResult(
                        false,
                        OptionalInt.of(process.exitValue()),
                        TerminationReport.notAttempted());
            }
        } catch (InterruptedException exception) {
            try {
                terminate(process, limits);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new HarnessException("HARNESS_RUN_INTERRUPTED", "Interrupted while waiting for the target process", exception);
        }

        TerminationReport termination = terminate(process, limits);
        OptionalInt exitCode = process.isAlive()
                ? OptionalInt.empty()
                : OptionalInt.of(process.exitValue());
        return new SupervisionResult(true, exitCode, termination);
    }

    /** 对仍存活的根进程和已观察后代执行分级终止。 */
    TerminationReport terminate(Process process, ProcessLimits limits) throws HarnessException {
        Set<ProcessHandle> tracked = new LinkedHashSet<>();
        tracked.add(process.toHandle());
        process.descendants().forEach(tracked::add);

        int gracefulSignals = signal(tracked, false);
        waitUntilStopped(tracked, limits.gracefulTerminationTimeout());

        process.descendants().forEach(tracked::add);
        int forcedSignals = signal(tracked, true);
        waitUntilStopped(tracked, limits.forcedTerminationTimeout());

        List<Long> survivors = tracked.stream()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .sorted()
                .toList();
        TerminationReport report = new TerminationReport(
                true, gracefulSignals, forcedSignals, survivors);
        if (!survivors.isEmpty()) {
            throw new HarnessException(
                    "HARNESS_PROCESS_TREE_CLEANUP_FAILED",
                    "Target processes remain alive after the termination budget expired; PIDs: " + survivors);
        }
        return report;
    }

    private static int signal(Set<ProcessHandle> handles, boolean forcibly) {
        List<ProcessHandle> ordered = new ArrayList<>(handles);
        ordered.sort(Comparator.comparingInt(ProcessSupervisor::depth).reversed());
        int signals = 0;
        for (ProcessHandle handle : ordered) {
            if (!handle.isAlive()) {
                continue;
            }
            boolean accepted = forcibly ? handle.destroyForcibly() : handle.destroy();
            if (accepted) {
                signals++;
            }
        }
        return signals;
    }

    private static int depth(ProcessHandle handle) {
        int depth = 0;
        java.util.Optional<ProcessHandle> parent = handle.parent();
        while (parent.isPresent() && depth < 1_024) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
    }

    private static void waitUntilStopped(Set<ProcessHandle> handles, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
