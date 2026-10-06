package org.example.algorithmdebug.codepath.launcher;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Agent CodePath Plan 在目标 JVM 中使用的最小严格镜像。 */
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
        String questionToAnswer,
        InvestigationStatus investigationStatus,
        InvestigationBinding investigationBinding,
        Intent intent,
        Instant createdAt) {

    private static final String CURRENT_SCHEMA_VERSION = "7.0";
    private static final Set<String> LEGACY_SCHEMA_VERSIONS = Set.of("5.0", "6.0");
    private static final int DEFAULT_SCOPE_START_ORDINAL = 1;
    private static final int DEFAULT_MAX_MATCHED_SCOPES = 10_000;
    private static final int MAX_SCOPE_START_ORDINAL = 1_000_000;
    private static final int MAX_QUESTION_LENGTH = 2_048;

    LauncherCodePathPlan {
        boolean current = CURRENT_SCHEMA_VERSION.equals(schemaVersion);
        boolean legacy = LEGACY_SCHEMA_VERSIONS.contains(schemaVersion);
        if (!current && !legacy) {
            throw new IllegalArgumentException("Unsupported CodePath Plan version");
        }
        methodSelections = List.copyOf(methodSelections);
        scopeConditions = scopeConditions == null ? List.of() : List.copyOf(scopeConditions);
        captureMode = captureMode == null ? CaptureMode.TRACE : captureMode;
        scopeStartOrdinal = scopeStartOrdinal == 0
                ? DEFAULT_SCOPE_START_ORDINAL : scopeStartOrdinal;
        maxMatchedScopes = maxMatchedScopes == 0
                ? DEFAULT_MAX_MATCHED_SCOPES : maxMatchedScopes;
        if (scopeStartOrdinal < DEFAULT_SCOPE_START_ORDINAL
                || scopeStartOrdinal > MAX_SCOPE_START_ORDINAL
                || maxMatchedScopes < 1
                || maxMatchedScopes > DEFAULT_MAX_MATCHED_SCOPES) {
            throw new IllegalArgumentException("CodePath scope window is outside the safe range");
        }
        if (scopeMethodKey == null && (scopeStartOrdinal != DEFAULT_SCOPE_START_ORDINAL
                || maxMatchedScopes != DEFAULT_MAX_MATCHED_SCOPES)) {
            throw new IllegalArgumentException("A non-default scope window requires scopeMethodKey");
        }
        if (current) {
            if (investigationStatus != InvestigationStatus.STRUCTURED
                    || investigationBinding == null || intent != null
                    || questionToAnswer == null || questionToAnswer.isBlank()
                    || questionToAnswer.length() > MAX_QUESTION_LENGTH
                    || !caseId.equals(investigationBinding.caseId())
                    || !analysisId.equals(investigationBinding.analysisId())) {
                throw new IllegalArgumentException(
                        "Current CodePath Plan requires a matching structured investigation binding");
            }
        } else if (intent == null || questionToAnswer != null
                || investigationStatus != null || investigationBinding != null) {
            throw new IllegalArgumentException(
                    "Legacy CodePath Plan must contain only legacy intent");
        }
    }

    /** 旧 Launcher 单元和归档重放使用的 v5/v6 构造器。 */
    LauncherCodePathPlan(
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
        this(schemaVersion, planId, caseId, analysisId, targetTest, methodSelections,
                scopeMethodKey, scopeConditions, captureMode, scopeStartOrdinal,
                maxMatchedScopes, budget, rationale, null, null, null, intent, createdAt);
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

    enum InvestigationStatus {
        STRUCTURED,
        LEGACY_UNSTRUCTURED
    }

    /** 仅镜像 Launcher 执行后归档和后处理所需的不可变绑定字段。 */
    record InvestigationBinding(
            String schemaVersion,
            String caseId,
            String analysisId,
            String gapId,
            List<String> hypothesisIds,
            List<String> predicateIds,
            List<String> basedOnEvidenceIds) {
        private static final String CURRENT_SCHEMA_VERSION = "1.0";
        private static final int MAX_ID_LENGTH = 128;
        private static final int MAX_HYPOTHESES = 32;
        private static final int MAX_PREDICATES = 8;
        private static final int MAX_EVIDENCE_REFERENCES = 128;

        InvestigationBinding {
            hypothesisIds = checkedIds(
                    hypothesisIds, "hypothesisIds", 1, MAX_HYPOTHESES);
            predicateIds = checkedIds(
                    predicateIds, "predicateIds", 1, MAX_PREDICATES);
            basedOnEvidenceIds = checkedIds(
                    basedOnEvidenceIds, "basedOnEvidenceIds", 0,
                    MAX_EVIDENCE_REFERENCES);
            if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)
                    || invalidId(caseId) || invalidId(analysisId) || invalidId(gapId)) {
                throw new IllegalArgumentException("Invalid investigation binding");
            }
        }

        private static List<String> checkedIds(
                List<String> values, String field, int minimum, int maximum) {
            if (values == null) {
                throw new IllegalArgumentException(field + " must not be null");
            }
            List<String> checked = List.copyOf(values);
            if (checked.size() < minimum || checked.size() > maximum
                    || checked.stream().anyMatch(InvestigationBinding::invalidId)
                    || new HashSet<>(checked).size() != checked.size()) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return checked;
        }

        private static boolean invalidId(String value) {
            return value == null || value.isBlank() || value.length() > MAX_ID_LENGTH;
        }
    }
}
