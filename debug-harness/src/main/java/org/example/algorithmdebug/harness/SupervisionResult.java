package org.example.algorithmdebug.harness;

import java.util.OptionalInt;

/** ProcessSupervisor 返回的进程退出与清理事实。 */
record SupervisionResult(
        boolean timedOut,
        OptionalInt exitCode,
        TerminationReport termination) {

    SupervisionResult {
        if (exitCode == null || termination == null) {
            throw new IllegalArgumentException("监管结果参数不能为空");
        }
        if (timedOut != termination.attempted()) {
            throw new IllegalArgumentException("超时状态必须与清理状态一致");
        }
    }
}
