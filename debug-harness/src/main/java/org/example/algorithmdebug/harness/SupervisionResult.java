package org.example.algorithmdebug.harness;

import java.util.OptionalInt;

/** ProcessSupervisor 返回的进程退出与清理事实。 */
record SupervisionResult(
        boolean timedOut,
        OptionalInt exitCode,
        TerminationReport termination) {

    SupervisionResult {
        if (exitCode == null || termination == null) {
            throw new IllegalArgumentException("Supervision result arguments must not be null");
        }
        if (timedOut != termination.attempted()) {
            throw new IllegalArgumentException("Timeout status must match cleanup status");
        }
    }
}
