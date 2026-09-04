package org.example.algorithmdebug.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** CodePath 精确方法 Raw Trace 的通用方法统计和最近选中祖先摘要。 */
public record MethodPathSummary(
        String schemaVersion,
        EvidenceId evidenceId,
        CaseId caseId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        ArtifactReference rawTrace,
        List<MethodStatistic> methods,
        List<ObservedPath> observedPaths,
        List<PathAnomaly> anomalies,
        Optional<ScopeSummary> scope,
        boolean truncated,
        Instant createdAt) {

    /** 校验身份、精度和摘要硬上限。 */
    public MethodPathSummary {
        if (!SchemaVersions.METHOD_PATH_SUMMARY.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported MethodPathSummary schemaVersion");
        }
        evidenceId = ContractChecks.requireNonNull(evidenceId, "evidenceId");
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        rawTrace = ContractChecks.requireNonNull(rawTrace, "rawTrace");
        methods = ContractChecks.immutableList(methods, "methods");
        observedPaths = ContractChecks.immutableList(observedPaths, "observedPaths");
        anomalies = ContractChecks.immutableList(anomalies, "anomalies");
        scope = scope == null ? Optional.empty() : scope;
        if (methods.size() > NormalizationBudget.MAX_METHODS
                || observedPaths.size() > NormalizationBudget.MAX_RELATIONSHIPS
                || anomalies.size() > 10_000) {
            throw new IllegalArgumentException("The method-path summary exceeds the hard limit");
        }
        createdAt = ContractChecks.requireNonNull(createdAt, "createdAt");
    }

    /** 兼容没有 Scope 派生数据的历史摘要。 */
    public MethodPathSummary(
            String schemaVersion,
            EvidenceId evidenceId,
            CaseId caseId,
            AnalysisId analysisId,
            RunId runId,
            PlanId planId,
            CollectionId collectionId,
            ArtifactReference rawTrace,
            List<MethodStatistic> methods,
            List<ObservedPath> observedPaths,
            List<PathAnomaly> anomalies,
            boolean truncated,
            Instant createdAt) {
        this(schemaVersion, evidenceId, caseId, analysisId, runId, planId,
                collectionId, rawTrace, methods, observedPaths, anomalies,
                Optional.empty(), truncated, createdAt);
    }

    /** 一个计划方法的进入、退出和深度统计。 */
    public record MethodStatistic(
            String methodKey, long enterCount, long exitCount, int minDepth, int maxDepth,
            TraceProvenance firstObservation, TraceProvenance lastObservation) {
        public MethodStatistic {
            methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 2_048, false);
            if (enterCount < 0 || exitCount < 0 || minDepth < 0 || maxDepth < minDepth) {
                throw new IllegalArgumentException("Method statistics are invalid");
            }
            firstObservation = ContractChecks.requireNonNull(firstObservation, "firstObservation");
            lastObservation = ContractChecks.requireNonNull(lastObservation, "lastObservation");
        }
    }

    /** 精确方法事件能够确认的最近选中祖先关系。 */
    public record ObservedPath(
            String ancestorMethodKey, String descendantMethodKey,
            String relationshipType, long count, TraceProvenance firstObservation) {
        public ObservedPath {
            ancestorMethodKey = ContractChecks.requireBoundedText(
                    ancestorMethodKey, "ancestorMethodKey", 2_048, false);
            descendantMethodKey = ContractChecks.requireBoundedText(
                    descendantMethodKey, "descendantMethodKey", 2_048, false);
            if (!"NEAREST_SELECTED_ANCESTOR".equals(relationshipType)) {
                throw new IllegalArgumentException(
                        "An exact CodePath method Trace may only declare NEAREST_SELECTED_ANCESTOR");
            }
            if (count < 1) throw new IllegalArgumentException("count must be positive");
            firstObservation = ContractChecks.requireNonNull(firstObservation, "firstObservation");
        }
    }

    /** 一个可选 Scope 方法的重复调用摘要。 */
    public record ScopeSummary(
            String methodKey,
            int invocationCount,
            int completeInvocationCount,
            int incompleteInvocationCount,
            List<ScopeInvocation> invocations,
            List<PathVariant> pathVariants) {
        public ScopeSummary {
            methodKey = ContractChecks.requireBoundedText(methodKey, "methodKey", 2_048, false);
            invocations = ContractChecks.immutableList(invocations, "invocations");
            pathVariants = ContractChecks.immutableList(pathVariants, "pathVariants");
            if (invocationCount < 0 || completeInvocationCount < 0 || incompleteInvocationCount < 0
                    || completeInvocationCount + incompleteInvocationCount != invocationCount
                    || invocations.size() > NormalizationBudget.MAX_HITS
                    || pathVariants.size() > NormalizationBudget.MAX_RELATIONSHIPS) {
                throw new IllegalArgumentException("Scope summary counts or limits are invalid");
            }
            Set<Integer> ordinals = invocations.stream()
                    .map(ScopeInvocation::ordinal).collect(Collectors.toSet());
            if (ordinals.size() != invocations.size()) {
                throw new IllegalArgumentException("Scope invocation ordinals must be unique");
            }
            Set<String> pathIds = pathVariants.stream()
                    .map(PathVariant::pathId).collect(Collectors.toSet());
            if (pathIds.size() != pathVariants.size()
                    || invocations.stream().flatMap(value -> value.pathId().stream())
                    .anyMatch(value -> !pathIds.contains(value))) {
                throw new IllegalArgumentException("Scope invocation pathId is invalid");
            }
        }
    }

    /** 一次 Scope 方法进入到对应退出之间的有界结构事实。 */
    public record ScopeInvocation(
            int ordinal,
            long startEventId,
            Optional<Long> endEventId,
            int eventCount,
            int maxDepth,
            Optional<String> pathId,
            boolean truncated) {
        public ScopeInvocation {
            endEventId = endEventId == null ? Optional.empty() : endEventId;
            pathId = pathId == null ? Optional.empty() : pathId;
            pathId = pathId.map(value ->
                    ContractChecks.requireBoundedText(value, "pathId", 64, false));
            if (ordinal < 1 || startEventId < 1 || eventCount < 1 || maxDepth < 0
                    || endEventId.filter(value -> value < startEventId).isPresent()
                    || (endEventId.isEmpty() && pathId.isPresent())) {
                throw new IllegalArgumentException("Scope invocation is invalid");
            }
        }
    }

    /** 完整 Scope 调用中相同有序方法进入序列的聚类。 */
    public record PathVariant(
            String pathId,
            int occurrenceCount,
            List<Integer> representativeInvocationOrdinals,
            List<String> representativeMethodSequence) {
        public PathVariant {
            pathId = ContractChecks.requireBoundedText(pathId, "pathId", 64, false);
            representativeInvocationOrdinals = ContractChecks.immutableList(
                    representativeInvocationOrdinals, "representativeInvocationOrdinals");
            representativeMethodSequence = ContractChecks.immutableList(
                    representativeMethodSequence, "representativeMethodSequence");
            if (!pathId.matches("PATH_[0-9]+") || occurrenceCount < 1
                    || representativeMethodSequence.isEmpty()
                    || representativeInvocationOrdinals.size() > NormalizationBudget.MAX_HITS
                    || representativeInvocationOrdinals.stream().anyMatch(value -> value == null || value < 1)
                    || representativeMethodSequence.stream().anyMatch(value ->
                    value == null || value.isBlank() || value.length() > 2_048)) {
                throw new IllegalArgumentException("Path variant is invalid");
            }
        }
    }

    /** 未配对、顺序或深度异常。 */
    public record PathAnomaly(String code, String detail, TraceProvenance provenance) {
        public PathAnomaly {
            code = ContractChecks.requireBoundedText(code, "code", 128, false);
            detail = ContractChecks.requireBoundedText(detail, "detail", 2_048, false);
            provenance = ContractChecks.requireNonNull(provenance, "provenance");
        }
    }
}
