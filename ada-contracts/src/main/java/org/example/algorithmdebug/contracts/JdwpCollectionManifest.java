package org.example.algorithmdebug.contracts;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 对 JDWP 双进程运行生成的可信、可移植 Manifest。
 *
 * <p>外部 Collector Manifest 作为 Raw Artifact 保存；本契约只暴露相对路径和经过校验的运行事实。</p>
 */
public record JdwpCollectionManifest(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        RunId runId,
        PlanId planId,
        CollectionId collectionId,
        String toolName,
        String toolVersion,
        JdwpCollectionCompletion completion,
        String completionReason,
        JdwpCollectionStage stage,
        boolean targetStarted,
        boolean collectorStarted,
        int targetExitCode,
        int collectorExitCode,
        boolean timedOut,
        boolean truncated,
        long eventCount,
        long rawBytes,
        @JsonAlias("hitCounts") Map<String, Integer> observedHitCounts,
        Map<String, Integer> matchedHitCounts,
        Map<String, Integer> capturedHitCounts,
        Map<String, Integer> conditionUnavailableCounts,
        Map<String, Integer> installedLocations,
        Optional<AgentFailureDiagnostic> agentFailure,
        String rawTraceRelativePath,
        String collectorManifestRelativePath,
        String targetStdoutLog,
        String targetStderrLog,
        String collectorStdoutLog,
        String collectorStderrLog,
        Instant startedAt,
        Instant completedAt) {

    /** 校验身份、工具、计数、Hash、路径和时间顺序，并防御性复制计数器。 */
    public JdwpCollectionManifest {
        if (!SchemaVersions.JDWP_COLLECTION_MANIFEST.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported JdwpCollectionManifest schemaVersion");
        }
        caseId = ContractChecks.requireNonNull(caseId, "caseId");
        contextId = ContractChecks.requireNonNull(contextId, "contextId");
        analysisId = ContractChecks.requireNonNull(analysisId, "analysisId");
        runId = ContractChecks.requireNonNull(runId, "runId");
        planId = ContractChecks.requireNonNull(planId, "planId");
        collectionId = ContractChecks.requireNonNull(collectionId, "collectionId");
        toolName = ContractChecks.requireBoundedText(toolName, "toolName", 128, false);
        toolVersion = ContractChecks.requireBoundedText(toolVersion, "toolVersion", 256, false);
        completion = ContractChecks.requireNonNull(completion, "completion");
        completionReason = ContractChecks.requireBoundedText(completionReason, "completionReason", 128, false);
        stage = ContractChecks.requireNonNull(stage, "stage");
        if (eventCount < 0 || rawBytes < 0) {
            throw new IllegalArgumentException("JDWP event and Raw byte counts must not be negative");
        }
        observedHitCounts = immutableCounters(observedHitCounts, "observedHitCounts");
        matchedHitCounts = immutableCounters(
                matchedHitCounts == null ? observedHitCounts : matchedHitCounts,
                "matchedHitCounts");
        capturedHitCounts = immutableCounters(
                capturedHitCounts == null ? matchedHitCounts : capturedHitCounts,
                "capturedHitCounts");
        conditionUnavailableCounts = immutableCounters(
                conditionUnavailableCounts == null ? Map.of() : conditionUnavailableCounts,
                "conditionUnavailableCounts");
        installedLocations = immutableCounters(installedLocations, "installedLocations");
        agentFailure = ContractChecks.requireNonNull(agentFailure, "agentFailure");
        if (timedOut != (completion == JdwpCollectionCompletion.TIMED_OUT)) {
            throw new IllegalArgumentException("timedOut does not match completion");
        }
        if (truncated != (completion == JdwpCollectionCompletion.TRUNCATED)) {
            throw new IllegalArgumentException("truncated does not match completion");
        }
        if (completion == JdwpCollectionCompletion.AGENT_FAILED && agentFailure.isEmpty()) {
            throw new IllegalArgumentException("AGENT_FAILED must include agentFailure");
        }
        if (!targetStarted && targetExitCode != -1) {
            throw new IllegalArgumentException("targetwhen not started targetExitCode must be -1");
        }
        if (!collectorStarted && collectorExitCode != -1) {
            throw new IllegalArgumentException("Collector when not started collectorExitCode must be -1");
        }
        if (completion == JdwpCollectionCompletion.SUCCESS
                && (!targetStarted || !collectorStarted || targetExitCode != 0
                || collectorExitCode != 0 || stage == JdwpCollectionStage.FAILED
                || installedLocations.values().stream().mapToInt(Integer::intValue).sum() == 0)) {
            throw new IllegalArgumentException("SUCCESS is inconsistent with process, Raw Trace, or stage facts");
        }
        rawTraceRelativePath = ContractChecks.requirePortableRelativePath(
                rawTraceRelativePath, "rawTraceRelativePath");
        collectorManifestRelativePath = ContractChecks.requirePortableRelativePath(
                collectorManifestRelativePath, "collectorManifestRelativePath");
        targetStdoutLog = ContractChecks.requirePortableRelativePath(targetStdoutLog, "targetStdoutLog");
        targetStderrLog = ContractChecks.requirePortableRelativePath(targetStderrLog, "targetStderrLog");
        collectorStdoutLog = ContractChecks.requirePortableRelativePath(
                collectorStdoutLog, "collectorStdoutLog");
        collectorStderrLog = ContractChecks.requirePortableRelativePath(
                collectorStderrLog, "collectorStderrLog");
        startedAt = ContractChecks.requireNonNull(startedAt, "startedAt");
        completedAt = ContractChecks.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }

    /** 兼容只有单一 hitCounts 的历史 v2 Manifest 构造方式。 */
    public JdwpCollectionManifest(
            String schemaVersion,
            CaseId caseId,
            ContextId contextId,
            AnalysisId analysisId,
            RunId runId,
            PlanId planId,
            CollectionId collectionId,
            String toolName,
            String toolVersion,
            JdwpCollectionCompletion completion,
            String completionReason,
            JdwpCollectionStage stage,
            boolean targetStarted,
            boolean collectorStarted,
            int targetExitCode,
            int collectorExitCode,
            boolean timedOut,
            boolean truncated,
            long eventCount,
            long rawBytes,
            Map<String, Integer> hitCounts,
            Map<String, Integer> installedLocations,
            Optional<AgentFailureDiagnostic> agentFailure,
            String rawTraceRelativePath,
            String collectorManifestRelativePath,
            String targetStdoutLog,
            String targetStderrLog,
            String collectorStdoutLog,
            String collectorStderrLog,
            Instant startedAt,
            Instant completedAt) {
        this(schemaVersion, caseId, contextId, analysisId, runId, planId, collectionId,
                toolName, toolVersion, completion, completionReason, stage,
                targetStarted, collectorStarted, targetExitCode, collectorExitCode,
                timedOut, truncated, eventCount, rawBytes,
                hitCounts, hitCounts, hitCounts, Map.of(), installedLocations,
                agentFailure, rawTraceRelativePath, collectorManifestRelativePath,
                targetStdoutLog, targetStderrLog, collectorStdoutLog, collectorStderrLog,
                startedAt, completedAt);
    }

    private static Map<String, Integer> immutableCounters(
            Map<String, Integer> values, String fieldName) {
        ContractChecks.requireNonNull(values, fieldName);
        LinkedHashMap<String, Integer> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String checkedKey = ContractChecks.requireOpaqueId(key, fieldName + " key");
            if (value == null || value < 0) {
                throw new IllegalArgumentException(fieldName + " value must not be negative or null");
            }
            copied.put(checkedKey, value);
        });
        if (copied.size() > 20) {
            throw new IllegalArgumentException(fieldName + " count must not exceed 20");
        }
        return Collections.unmodifiableMap(copied);
    }
}
