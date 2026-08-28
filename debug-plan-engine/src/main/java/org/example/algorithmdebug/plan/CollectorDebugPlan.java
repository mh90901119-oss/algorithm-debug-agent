package org.example.algorithmdebug.plan;

import java.util.List;

/** 锁定 Collector commit `1ef7d22` 的包内 JSON DTO，不作为 Agent 公共契约暴露。 */
record CollectorDebugPlan(
        String schemaVersion,
        String sessionId,
        Target target,
        boolean resumeOnAttach,
        long idleTimeoutMillis,
        int maxEvents,
        List<Tracepoint> tracepoints) {

    record Target(String host, int port) {
    }

    record Tracepoint(
            String id,
            String className,
            int line,
            String methodName,
            String methodDescriptor,
            int maxHits,
            List<Integer> captureOnHits,
            Capture capture) {
    }

    record Capture(
            boolean locals,
            boolean stack,
            int maxFrames,
            int maxDepth,
            int maxItems,
            int maxStringLength,
            List<String> localNames,
            List<String> fieldPaths) {
    }
}
