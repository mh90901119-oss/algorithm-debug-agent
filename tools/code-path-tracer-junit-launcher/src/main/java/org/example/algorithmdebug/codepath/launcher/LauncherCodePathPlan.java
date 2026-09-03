package org.example.algorithmdebug.codepath.launcher;

import java.time.Instant;
import java.util.List;

/** Agent CodePath Plan v4 在目标 JVM 中使用的最小镜像。 */
record LauncherCodePathPlan(
        String schemaVersion,
        String planId,
        String caseId,
        String analysisId,
        TargetTest targetTest,
        List<MethodSelection> methodSelections,
        String scopeMethodKey,
        Budget budget,
        String rationale,
        Intent intent,
        Instant createdAt) {

    record TargetTest(String className, String methodName) {
        String selector() {
            return className + "#" + methodName;
        }
    }

    record MethodSelection(MethodSelector selector, List<Projection> projections) {
    }

    record MethodSelector(String methodKey, String className, String methodName, String descriptor) {
    }

    record Projection(
            String name,
            ProjectionSource source,
            Integer argumentIndex,
            List<String> fieldPath,
            boolean required) {
    }

    enum ProjectionSource {
        ARGUMENT,
        RETURN
    }

    record Budget(long maxEvents, long maxBytes, long timeoutMillis) {
    }

    record Intent(
            String questionToAnswer,
            String hypothesis,
            List<String> basedOnEvidenceIds,
            List<String> expectedObservations) {
    }
}
