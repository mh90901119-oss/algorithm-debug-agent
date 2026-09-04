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
            int maxObservedHits,
            int maxCapturedHits,
            int captureFirstMatchedHits,
            int captureEveryMatchedHits,
            List<Condition> conditions,
            Capture capture) {
    }

    record Condition(
            String valuePath,
            String operator,
            String expectedType,
            String expectedValue) {
    }

    record Capture(
            boolean stack,
            int maxFrames,
            int maxStringLength,
            List<String> valuePaths) {
    }
}
