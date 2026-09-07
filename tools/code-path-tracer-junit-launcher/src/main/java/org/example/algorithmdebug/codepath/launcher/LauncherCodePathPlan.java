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
        List<ScopeCondition> scopeConditions,
        CaptureMode captureMode,
        int scopeStartOrdinal,
        int maxMatchedScopes,
        Budget budget,
        String rationale,
        Intent intent,
        Instant createdAt) {

    LauncherCodePathPlan {
        if (!("5.0".equals(schemaVersion) || "6.0".equals(schemaVersion))) {
            throw new IllegalArgumentException("Unsupported CodePath Plan version");
        }
        methodSelections = List.copyOf(methodSelections);
        scopeConditions = scopeConditions == null ? List.of() : List.copyOf(scopeConditions);
        captureMode = captureMode == null ? CaptureMode.TRACE : captureMode;
        scopeStartOrdinal = scopeStartOrdinal == 0 ? 1 : scopeStartOrdinal;
        maxMatchedScopes = maxMatchedScopes == 0 ? 10_000 : maxMatchedScopes;
        if (scopeStartOrdinal < 1 || scopeStartOrdinal > 1_000_000
                || maxMatchedScopes < 1 || maxMatchedScopes > 10_000) {
            throw new IllegalArgumentException("CodePath scope window is outside the safe range");
        }
        if (scopeMethodKey == null && (scopeStartOrdinal != 1 || maxMatchedScopes != 10_000)) {
            throw new IllegalArgumentException("A non-default scope window requires scopeMethodKey");
        }
    }

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

    record ScopeCondition(String projectionName, ScalarType expectedType, Object expectedValue) {
    }

    enum ScalarType {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        NULL
    }

    enum CaptureMode {
        TRACE,
        AGGREGATE
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
